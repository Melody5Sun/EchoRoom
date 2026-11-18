import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT=Path(__file__).with_name('audit-broadcast.py')

class BroadcastAuditTest(unittest.TestCase):
    def audit(self, events, legacy=False, minimum_hold=None):
        scratch=SCRIPT.parent/'results'
        scratch.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as folder:
            path=Path(folder)/'events.jsonl'
            path.write_text('\n'.join(json.dumps(event) for event in events),encoding='utf-8')
            extra=[] if minimum_hold is None else ['--minimum-hold-seconds',str(minimum_hold),
                '--expected-receivers','1','--expected-publishers','1','--continuity-output',str(Path(folder)/'continuity.json')]
            return subprocess.run([sys.executable,str(SCRIPT),*(['--legacy-open-intervals'] if legacy else []),*extra,str(path)],
                text=True,encoding='utf-8',capture_output=True)

    def event(self,kind,at,**extra):
        return dict(audit=kind,run='test',room='1',receiver=1,role='receiver',at=at,**extra)

    def accepted(self,identifier,sent):
        return dict(audit='accepted',run='test',room='1',receiver=1,role='publisher',
            at=sent+20,sentAt=sent,id=identifier)

    def test_pre_admission_publish_is_not_a_missing_delivery(self):
        events=[self.event('open',0),self.accepted(1,100),self.event('ready',1000),
            self.accepted(2,1500),self.event('message',1600,id=2),self.event('close',4000)]
        result=self.audit(events)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(json.loads(result.stdout)['expected'],1)
        # Historical TCP-open accounting falsely counts the pre-admission message.
        self.assertEqual(self.audit(events,legacy=True).returncode,1)

    def test_missing_post_ready_message_still_fails(self):
        result=self.audit([self.event('open',0),self.event('ready',1000),
            self.accepted(2,1500),self.event('close',4000)])
        self.assertEqual(result.returncode,1)
        self.assertEqual(json.loads(result.stdout)['missing'],1)

    def test_unready_transport_is_not_silently_accepted(self):
        result=self.audit([self.event('open',0),self.accepted(2,1500),self.event('close',4000)])
        self.assertNotEqual(result.returncode,0)
        self.assertIn('business-ready',result.stderr)

    def test_missing_close_marker_is_incomplete(self):
        result=self.audit([self.event('open',0),self.event('ready',1000),self.accepted(2,1500)])
        self.assertNotEqual(result.returncode,0)
        self.assertIn('close marker missing',result.stderr)

    def test_message_confirmed_in_another_room_is_rejected(self):
        accepted=self.accepted(2,1500)
        accepted['room']='2'
        result=self.audit([self.event('ready',1000),accepted,
                           self.event('message',1600,id=2),self.event('close',4000)])
        self.assertEqual(result.returncode,1)
        self.assertEqual(json.loads(result.stdout)['crossRoom'],1)

    def test_continuity_uses_business_ready_duration(self):
        events=[self.event('open',0),self.event('ready',1000),self.accepted(2,1500),
            self.event('message',1600,id=2),self.event('close',4000),
            dict(audit='ready',run='test',room='1',receiver=1,role='publisher',at=900),
            dict(audit='close',run='test',room='1',receiver=1,role='publisher',at=4100)]
        self.assertEqual(self.audit(events,minimum_hold=2).returncode,0)
        short=self.audit(events,minimum_hold=5)
        self.assertNotEqual(short.returncode,0)
        self.assertIn('shorter than required',short.stderr)

if __name__=='__main__':
    unittest.main()
