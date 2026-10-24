import unittest
from .identity import resolve_subject


class IdentityTests(unittest.TestCase):
    def resolve(self, text, sender='111111', mentions=()):
        return resolve_subject(text,sender,mentions)
    def test_self_is_sender(self):
        self.assertEqual(self.resolve('我在干什么')[0],'111111')
        self.assertEqual(self.resolve('我正在听什么','222222')[0],'222222')
    def test_owner_needs_live_lookup(self):
        self.assertEqual(self.resolve('我想知道群主在干什么')[0],'group_owner')
    def test_explicit(self):
        self.assertEqual(self.resolve('他在干什么',mentions=['333333'])[0],'333333')
        self.assertEqual(self.resolve('222222在听什么')[0],'222222')
        self.assertIsNotNone(self.resolve('站长在干什么')[1])
    def test_ambiguous(self):
        self.assertIsNotNone(self.resolve('他呢')[1])
        self.assertIsNotNone(self.resolve('我和群主在干什么')[1])
        self.assertIsNotNone(self.resolve('我在干什么',mentions=['333333'])[1])

    def test_context_questions(self):
        for text in ('我的电量多少', '我的网络怎么样', '我的音量多少', '我的存储还剩多少', '我的采集权限开了吗'):
            self.assertEqual(self.resolve(text)[0], '111111')
