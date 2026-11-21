"""Run old/new Live binaries against an isolated schema, Redis and MQ topics."""
import argparse
import hashlib
import json
from pathlib import Path
import secrets
import subprocess
import time
import urllib.error
import urllib.request

root = Path(__file__).resolve().parent.parent
results = (root/'load-tests/results').resolve()
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--old-jar', required=True, type=Path)
args = parser.parse_args()
old_jar = args.old_jar.resolve(strict=True)
new_jar = root/'live-service/target/live-service-0.1.0-SNAPSHOT.jar'
if not old_jar.is_relative_to(root/'.tools') or not new_jar.is_file():
    parser.error('Use the historical artifact under ignored .tools and the built current artifact')
suffix = secrets.token_hex(5)
run = 'rollback-'+suffix
schema = 'echoroom_rb_'+suffix
db_user = 'rb_'+suffix
db_password = secrets.token_urlsafe(24)
redis_name = run+'-redis'
app_name = run+'-live'
report = {'run':run,'schema':schema,'passed':False,'phases':[],
          'oldJarSha256':hashlib.sha256(old_jar.read_bytes()).hexdigest(),
          'newJarSha256':hashlib.sha256(new_jar.read_bytes()).hexdigest()}
active_app = False


def docker(arguments, data=None, timeout=40):
    return subprocess.run(['docker',*arguments],input=data,text=True,encoding='utf-8',
                          capture_output=True,check=True,timeout=timeout).stdout.strip()


def sql(query):
    return docker(['exec','-i','echoroom-ha-mysql-2-1','sh','-ec',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -h mysql-router -P 6446 -N -B'],
        f'USE {schema};\n'+query)


def api(path, body=None, user=2):
    request = urllib.request.Request(base+path,
        data=None if body is None else json.dumps(body).encode(),
        headers={'Content-Type':'application/json','X-User-Id':str(user),'X-User-Role':'USER'})
    with urllib.request.urlopen(request,timeout=5) as response:
        value = json.load(response)
    if not value.get('success'):
        raise RuntimeError('Isolated service probe was rejected')
    return value['data']


def wait_order(path):
    deadline = time.monotonic()+40
    while time.monotonic()<deadline:
        state = api(path)['status']
        if state=='SUCCESS':
            return
        if state!='PENDING':
            raise RuntimeError('Order reached unexpected terminal state '+state)
        time.sleep(.4)
    raise RuntimeError('Order did not complete')


def stop_app():
    global active_app
    if active_app:
        docker(['stop','--time','15',app_name],timeout=25)
        log = docker(['logs',app_name],timeout=15)
        (results/f'{run}-phase-{len(report["phases"])}.log').write_text(log,encoding='utf-8')
        docker(['rm',app_name])
        active_app = False


def boot(jar, label):
    global active_app, base
    report['phases'].append(label)
    docker(['run','-d','--name',app_name,'--network',network,'--memory','512m',
        '-p','127.0.0.1::8083','--env-file',str(env_path),
        '--mount',f'type=bind,source={jar},target=/app/app.jar,readonly',image])
    active_app = True
    info = json.loads(docker(['inspect',app_name]))[0]
    port = info['NetworkSettings']['Ports']['8083/tcp'][0]['HostPort']
    base = 'http://127.0.0.1:'+port
    deadline = time.monotonic()+100
    while time.monotonic()<deadline:
        try:
            with urllib.request.urlopen(base+'/actuator/health',timeout=3) as response:
                if json.load(response)['status']=='UP':
                    return
        except (TimeoutError,ConnectionError,urllib.error.URLError):
            pass
        if json.loads(docker(['inspect',app_name]))[0]['State']['Status']!='running':
            raise RuntimeError('Isolated Live process stopped during startup')
        time.sleep(1)
    raise RuntimeError('Isolated Live did not become healthy')


def reserve(user, label):
    order = run+'-'+label
    value = api('/api/v1/activities/9001/seckill',{'clientOrderNo':order},user=user)
    if value['orderNo']!=order:
        raise RuntimeError('Order identity changed')
    wait_order('/api/v1/activity-orders/'+order)
    return order


source = json.loads(docker(['inspect','echoroom-live-service-1']))[0]
db = json.loads(docker(['inspect','echoroom-ha-mysql-2-1']))[0]
network = next(iter(source['NetworkSettings']['Networks'].keys() & db['NetworkSettings']['Networks'].keys()))
image = source['Config']['Image']
env_path = results/(run+'.private.env')
settings = {
    'JAVA_OPTS':'-Xms128m -Xmx256m -XX:MaxDirectMemorySize=96m',
    'DB_URL':f'jdbc:mysql://mysql-router:6446/{schema}?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC',
    'DB_USERNAME':db_user,'DB_PASSWORD':db_password,
    'REDIS_HOST':redis_name,'REDIS_PORT':'6379',
    'NACOS_DISCOVERY_ENABLED':'false','NACOS_CONFIG_ENABLED':'false','SPRING_CONFIG_IMPORT':'',
    'ECHOROOM_RECONCILIATION_ENABLED':'false','ECHOROOM_REALTIME_CHANNEL':run,
    'ECHOROOM_NODE_ID':run,'LIVE_DB_POOL_MAX_SIZE':'8','DB_POOL_MAX_SIZE':'8',
    'ROCKETMQ_NAMESRV_ADDR':'namesrv-1:9876;namesrv-2:9876',
    'ROCKETMQ_GIFT_TOPIC':'RB_GIFT_'+suffix,'ROCKETMQ_ACTIVITY_TOPIC':'RB_ACTIVITY_'+suffix,
    'ROCKETMQ_GIFT_PRODUCER_GROUP':run+'-gift-p','ROCKETMQ_ACTIVITY_PRODUCER_GROUP':run+'-activity-p',
    'ROCKETMQ_GIFT_CONSUMER_GROUP':run+'-gift-c','ROCKETMQ_ACTIVITY_CONSUMER_GROUP':run+'-activity-c',
    'ROCKETMQ_DEAD_LETTER_ENABLED':'false','ROCKETMQ_CONSUME_THREAD_MIN':'1','ROCKETMQ_CONSUME_THREAD_MAX':'1',
}
env_path.write_text(''.join(f'{key}={value}\n' for key,value in settings.items()),encoding='utf-8')
try:
    docker(['exec','-i','echoroom-ha-mysql-2-1','sh','-ec',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -h mysql-router -P 6446'],
        f"CREATE DATABASE {schema}; CREATE USER '{db_user}'@'%' IDENTIFIED BY '{db_password}'; "
        f"GRANT ALL ON {schema}.* TO '{db_user}'@'%';")
    docker(['run','-d','--name',redis_name,'--network',network,'--memory','128m','redis:7.4-alpine'])
    for topic in (settings['ROCKETMQ_GIFT_TOPIC'],settings['ROCKETMQ_ACTIVITY_TOPIC']):
        docker(['exec','echoroom-ha-namesrv-2-1','sh','mqadmin','updateTopic','-n','namesrv-2:9876',
                '-c','EchoRoomHA','-t',topic],timeout=30)
    boot(old_jar,'old-v7')
    sql("""INSERT INTO live_room(id,anchor_id,title,status,stream_key_hash) VALUES(9001,1,'isolated rollback','LIVE','rollback');
        INSERT INTO wallet_account(user_id,balance) VALUES(1,0),(2,1000);
        INSERT INTO gift_catalog(code,name,price,status) VALUES('RB_ONE','rollback gift',1,'ACTIVE');
        INSERT INTO activity(id,room_id,name,stock,status,starts_at,ends_at)
        VALUES(9001,9001,'rollback',3,'ACTIVE',NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 3 HOUR);""")
    docker(['exec',redis_name,'redis-cli','SET','activity:{9001}:stock','3'])
    first = reserve(2,'before')
    before = sql('SELECT order_no,status FROM activity_order ORDER BY id;')
    stop_app()
    boot(new_jar,'upgrade-v9')
    if sql('SELECT reserved_count FROM activity WHERE id=9001;')!='1':
        raise RuntimeError('V8 did not backfill the old order')
    if sql('SELECT order_no,status FROM activity_order ORDER BY id;')!=before:
        raise RuntimeError('Upgrade changed the old business order')
    second = reserve(3,'new')
    stop_app()
    boot(old_jar,'rollback-old-binary')
    if api('/api/v1/activity-orders/'+first)['status']!='SUCCESS':
        raise RuntimeError('Old binary could not read the upgraded schema')
    gift = run+'-gift'
    api('/api/v1/live/rooms/9001/gifts',{'clientOrderNo':gift,'giftCode':'RB_ONE','quantity':1})
    wait_order('/api/v1/gift-orders/'+gift)
    # Isolated probe demonstrates why production rollback keeps seckill closed.
    third = reserve(4,'legacy-write')
    if sql('SELECT reserved_count FROM activity WHERE id=9001;')!='2':
        raise RuntimeError('Unexpected legacy reservation behavior')
    report['legacyWriterCounterLagVerified'] = True
    snapshot = sql('SELECT order_no,status FROM activity_order ORDER BY id;')
    stop_app()
    if sql((root/'scripts/preflight-inventory.sql').read_text(encoding='utf-8')):
        raise RuntimeError('Preflight found an oversold activity')
    sql((root/'scripts/recount-inventory.sql').read_text(encoding='utf-8'))
    boot(new_jar,'reupgrade-after-maintenance-recount')
    if sql('SELECT reserved_count FROM activity WHERE id=9001;')!='3':
        raise RuntimeError('Recount did not restore the reservation boundary')
    if sql('SELECT order_no,status FROM activity_order ORDER BY id;')!=snapshot:
        raise RuntimeError('Reupgrade changed business facts')
    try:
        reserve(5,'overflow')
        raise RuntimeError('Sold out activity accepted another order')
    except urllib.error.HTTPError as error:
        if error.code!=400:
            raise
    if sql('SELECT COUNT(*) FROM activity_order;')!='3':
        raise RuntimeError('Rollback cycle created an unexpected order')
    ledger = sql(f"SELECT COUNT(*),COUNT(DISTINCT biz_no),SUM(ABS(change_amount)) FROM wallet_ledger WHERE reference_id='{gift}';")
    if list(map(int,ledger.split()))!=[2,2,2]:
        raise RuntimeError('Rollback gift ledger is inconsistent')
    if sql('SELECT user_id,balance FROM wallet_account ORDER BY user_id;')!='1\t1\n2\t999':
        raise RuntimeError('Rollback gift changed wallet balances unexpectedly')
    report.update(passed=True,successfulActivityOrders=3,successfulGiftOrders=1,uniqueGiftLedgerEntries=2,
                  finalReserved=3,oldOrdersPreserved=True,soldOutRejected=True,
                  migrationVersion=sql('SELECT MAX(version) FROM flyway_schema_history WHERE success=1;'))
finally:
    stop_app()
    if docker(['ps','-aq','--filter','name=^/'+redis_name+'$']):
        docker(['stop',redis_name])
        docker(['rm',redis_name])
    (results/(run+'.json')).write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report))
