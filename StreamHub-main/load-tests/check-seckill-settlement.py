"""Reconcile isolated seckill client orders, authoritative inventory, Redis and Outbox."""
import argparse
import json
from pathlib import Path
import re
import subprocess

parser=argparse.ArgumentParser()
parser.add_argument('--fixture',required=True)
parser.add_argument('--run',required=True)
parser.add_argument('--summary',action='append',default=[])
args=parser.parse_args()
root=Path(__file__).resolve().parent.parent
results=(root/'load-tests/results').resolve()
if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9-]{0,63}',args.run):
    raise SystemExit('Use the exact safe run prefix')

def private(path):
    value=Path(path).resolve()
    if not value.is_relative_to(results): raise SystemExit('Input must stay within ignored results')
    return value

fixture=json.loads(private(args.fixture).read_text(encoding='utf-8-sig'))
if fixture['baseUrl'] not in ('http://localhost:8089','http://127.0.0.1:8089'):
    raise SystemExit('Only isolated local HA reconciliation is supported')
activities={int(value) for value in fixture.get('activityIds',[fixture['activityId']])}
summaries=[private(value) for value in args.summary] or sorted(results.glob(args.run+'-*-summary.json'))
if not summaries: raise SystemExit('No client summaries found')
client=[json.loads(file.read_text(encoding='utf-8-sig'))['metrics'] for file in summaries]
confirmed=sum(value['business_accepted_ratio']['passes'] for value in client)
recovered=sum(value.get('orders_ambiguous_accepted',{}).get('count',0) for value in client)

def docker(arguments, query=None):
    return subprocess.run(['docker',*arguments],input=query,text=True,encoding='utf-8',
        capture_output=True,timeout=30,check=True).stdout.strip()

def sql(query):
    return docker(['compose','-p','streamhub-ha','-f',str(root/'docker-compose.infra-ha.yml'),'exec','-T','mysql-2',
        'sh','-ec','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -h mysql-router -P 6446 -N -B streamhub'],query)

scope=f"o.order_no LIKE '{args.run}-%'"
rows=sql(f'SELECT o.activity_id,o.status,COUNT(*),COUNT(DISTINCT o.user_id) FROM activity_order o WHERE {scope} GROUP BY o.activity_id,o.status;')
orders={}
for row in rows.splitlines():
    activity,status,count,unique=row.split()
    if int(activity) not in activities or status!='SUCCESS' or count!=unique:
        raise RuntimeError('Unexpected activity, terminal status or repeated buyer: '+row)
    orders[int(activity)]=int(count)
total=sum(orders.values())
if not total or total!=confirmed+recovered:
    raise RuntimeError('Client confirmations and recovered orders disagree with successful database orders')

published=int(sql(f"""SELECT COUNT(*) FROM activity_order o
  JOIN mq_outbox c ON c.event_key=CONCAT('activity:create:',o.order_no) AND c.status='SENT'
  JOIN mq_outbox x ON x.event_key=CONCAT('activity:close:',o.order_no) AND x.status='SENT'
  WHERE {scope};"""))
if published!=total: raise RuntimeError('Each order must have both SENT Outbox events')

sentinel='streamhub-ha-sentinel-1-1'
master,port=docker(['exec',sentinel,'redis-cli','-p','26379','--raw','SENTINEL','get-master-addr-by-name','streamhub']).splitlines()
addresses={f'redis-{i}' for i in range(1,4)}
for index in range(1,4):
    addresses.add(docker(['inspect','--format','{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}',f'streamhub-ha-redis-{index}-1']))
if master not in addresses or port!='6379': raise RuntimeError('Unexpected Redis primary address')

def redis(command,key):
    return docker(['exec',sentinel,'redis-cli','-h',master,'-p',port,'--raw',command,key])

inventory=[]
for activity,count in sorted(orders.items()):
    stock,reserved=map(int,sql(f'SELECT stock,reserved_count FROM activity WHERE id={activity};').split())
    stock_redis=int(redis('GET',f'activity:{{{activity}}}:stock'))
    buyers=int(redis('SCARD',f'activity:{{{activity}}}:users'))
    if not 0<=reserved<=stock or count!=reserved or stock_redis!=stock-reserved or buyers!=reserved:
        raise RuntimeError(f'Inventory mismatch in activity {activity}')
    inventory.append({'activityId':activity,'stock':stock,'reserved':reserved,'redisRemaining':stock_redis,'redisBuyers':buyers})
report={'run':args.run,'clientConfirmed':confirmed,'recoveredServerAccepted':recovered,'successfulOrders':total,
    'sentOutboxEvents':2*published,'inventory':inventory,'passed':True,'capacityApproved':False}
(results/(args.run+'-seckill-settlement.json')).write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report))
