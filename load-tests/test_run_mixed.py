import unittest

import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location('run_mixed', Path(__file__).with_name('run-mixed.py'))
mixed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mixed)


class MixedWindowsTest(unittest.TestCase):
    def event(self, audit, at, receiver=1, run='mixed'):
        return dict(audit=audit, at=at, receiver=receiver, run=run, role='receiver')

    def test_business_ready_not_transport_open(self):
        events = [self.event('close', 0), self.event('ready', 10),
                  self.event('ready', 15, run='foreign'), self.event('close', 30)]
        self.assertEqual(mixed.continuous_windows(events, 'receiver', 'mixed', 1), [(10, 30)])

    def test_reconnect_does_not_join_discontinuous_intervals(self):
        events = [self.event('ready', 10), self.event('close', 30),
                  self.event('ready', 31), self.event('close', 40)]
        with self.assertRaisesRegex(ValueError, 'reconnected'):
            mixed.continuous_windows(events, 'receiver', 'mixed', 1)

    def test_unclosed_or_missing_target_cannot_pass(self):
        with self.assertRaisesRegex(ValueError, '2 complete'):
            mixed.continuous_windows([self.event('ready', 10)], 'receiver', 'mixed', 2)
