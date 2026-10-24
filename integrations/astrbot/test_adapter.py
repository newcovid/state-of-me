import asyncio
import importlib.util
import io
import json
import re
import urllib.error
import sys
import types
import unittest
from unittest.mock import patch, AsyncMock


class AdapterTests(unittest.IsolatedAsyncioTestCase):
    def test_binding_route_is_exact_and_independent_of_slash_prefix(self):
        for text in ('/状态绑定 ABCD2345', '状态绑定 ABCD2345', '状态绑定', '/状态绑定\tABCD2345'):
            self.assertIsNotNone(re.fullmatch(self.m.BIND_PATTERN, text))
        for text in ('解释状态绑定是什么意思', '搜索 /状态绑定 ABCD2345', '/状态绑定 ABCD2345 extra', '状态绑定其他命令'):
            self.assertIsNone(re.fullmatch(self.m.BIND_PATTERN, text))

    async def test_binding_error_explains_expired_code(self):
        event=self.event('/状态绑定 ABCD2345')
        event.stop_event=lambda:None
        event.plain_result=lambda text:text
        error=urllib.error.HTTPError('https://example.com',404,'expired',{},None)
        with patch('urllib.request.urlopen',side_effect=error):
            output=[r async for r in self.adapter.bind_account(event)]
        self.assertIn('重新生成',output[0])

    def setUp(self):
        event_module=types.ModuleType('astrbot.api.event')
        event_module.filter=types.SimpleNamespace(command=lambda *a,**k:lambda f:f, regex=lambda *a,**k:lambda f:f, on_llm_request=lambda **k:lambda f:f, llm_tool=lambda **k:lambda f:f)
        event_module.AstrMessageEvent=object
        star_module=types.ModuleType('astrbot.api.star')
        star_module.Context=object
        star_module.Star=object
        star_module.register=lambda *a:lambda c:c
        with patch.dict(sys.modules,{'astrbot.api.event':event_module,'astrbot.api.star':star_module}):
            spec=importlib.util.spec_from_file_location('integrations.astrbot.adapter_test_subject',__file__.replace('test_adapter.py','main.py'))
            self.m=importlib.util.module_from_spec(spec);spec.loader.exec_module(self.m)
        self.m.configuration=lambda:{'url':'https://example.com','groups':{'123456':{'bot_token':'x'*32}}}
        self.adapter=object.__new__(self.m.StateOfMe)

    def event(self,text,sender='111111',members=None):
        api=types.SimpleNamespace(call_action=AsyncMock(return_value=members))
        return types.SimpleNamespace(message_str=text,get_group_id=lambda:'123456',get_platform_name=lambda:'aiocqhttp',get_sender_id=lambda:sender,get_self_id=lambda:'999999',get_messages=lambda:[],bot=types.SimpleNamespace(api=api))

    async def test_history_uses_same_identity_and_separate_tool(self):
        data={'status':'available','devices':[{'device':'phone','days':[]}]}
        with patch('urllib.request.urlopen',return_value=io.BytesIO(json.dumps(data).encode())) as network:
            result=await self.adapter.get_usage_history(self.event('我今天用了多久手机'),1)
            self.assertIn('历史统计',result)
            self.assertIn('111111',result)
            self.assertEqual(network.call_args.args[0].full_url,'https://example.com/v1/bot/usage/111111?group_id=123456&days=1')
        with patch('urllib.request.urlopen') as network:
            self.assertIn('只支持',await self.adapter.get_usage_history(self.event('我用了多久'),999))
            network.assert_not_called()

    async def test_multiuser_reads_actual_sender_and_preserves_denial(self):
        self.m.configuration=lambda:{'url':'https://example.com','groups':{'123456':{'bot_token':'b'*32}}}
        with patch('urllib.request.urlopen',return_value=io.BytesIO(b'{"status":"not_shared","devices":[]}')) as network:
            result=json.loads(await self.adapter.get_my_status(self.event('我在干什么')))
            self.assertEqual(result['subject_qq'],'111111');self.assertEqual(result['status'],'not_shared')
            self.assertEqual(network.call_args.args[0].full_url,'https://example.com/v1/bot/status/111111?group_id=123456')
            self.assertEqual(network.call_args.args[0].get_header('Authorization'),'Bearer '+'b'*32)

    async def test_binding_uses_real_sender_and_stops_llm(self):
        self.m.configuration=lambda:{'url':'https://example.com','groups':{'123456':{'bot_token':'b'*32}}}
        event=self.event('/状态绑定 ABCD2345')
        event.stop_event=__import__('unittest.mock',fromlist=['Mock']).Mock()
        event.plain_result=lambda text:text
        with patch('urllib.request.urlopen',return_value=io.BytesIO(b'{"detail":"confirm in browser"}')) as network:
            output=[r async for r in self.adapter.bind_account(event)]
            body=json.loads(network.call_args.args[0].data)
            self.assertEqual(body,{'code':'ABCD2345','group_id':'123456','qq':'111111'})
            event.stop_event.assert_called_once();self.assertEqual(output,['confirm in browser'])

    async def test_authorized_desktop_context_is_preserved(self):
        data = {'devices': [{'device': 'desktop', 'app': 'Editor',
                'presence': {'state': 'active'}, 'usage_summary': {'keys': 40},
                'manual_status': {'text': 'Focus'}}]}
        general = self.m.validate_signals(data)['devices'][0]
        self.assertEqual(general['usage_summary'], {'keys':40})
        self.assertEqual(general['presence'], {'state':'active'})
        self.assertIn('manual_status', general)
        detailed = self.m.validate_signals(data)['devices'][0]
        self.assertEqual(detailed['usage_summary']['keys'], 40)

    async def test_other_self_never_fetches_owner(self):
        with patch('urllib.request.urlopen',return_value=io.BytesIO(b'{"status":"unbound","devices":[]}')) as network:
            r=await self.adapter.get_my_status(self.event('我在干什么'))
            self.assertEqual(json.loads(r)['status'],'unbound');self.assertIn('/status/111111?',network.call_args.args[0].full_url)

    async def test_live_owner_and_subject(self):
        event=self.event('群主在干什么',members=[{'user_id':222222,'role':'owner'}])
        with patch('urllib.request.urlopen',return_value=io.BytesIO(b'{"devices":[]}')):
            r=await self.adapter.get_my_status(event)
        self.assertIn('"subject_qq": "222222"',r)
        event.bot.api.call_action.assert_awaited_once_with('get_group_member_list',group_id=123456,no_cache=True)

    async def test_transferred_owner_and_api_failure(self):
        e=self.event('群主在干什么',members=[{'user_id':333333,'role':'owner'}])
        with patch('urllib.request.urlopen',return_value=io.BytesIO(b'{"status":"unbound","devices":[]}')) as network:
            r=await self.adapter.get_my_status(e);self.assertEqual(json.loads(r)['subject_qq'],'333333');self.assertIn('/status/333333?',network.call_args.args[0].full_url)
            network.reset_mock()
            e.bot.api.call_action.side_effect=RuntimeError('offline')
            r=await self.adapter.get_my_status(e);self.assertIn('不能确认',r);network.assert_not_called()

    async def test_context_payload_and_interpretation(self):
        payload = {'devices': [{'device': 'phone', 'network': {'validated': True},
                    'audio': {'connected_output_types': ['bluetooth']},
                    'resources': {'thermal_status': 2}, 'collector_health': {'usage_access': False}}]}
        with patch('urllib.request.urlopen', return_value=io.BytesIO(json.dumps(payload).encode())):
            result = await self.adapter.get_my_status(self.event('我的网络怎么样', sender='222222'))
        self.assertIn('不能证明正在使用耳机', result)
        self.assertEqual(json.loads(result.split('\n', 1)[1])['devices'], payload['devices'])

    async def test_empty_snapshot_has_actionable_guidance(self):
        with patch('urllib.request.urlopen', return_value=io.BytesIO(b'{"devices":[]}')):
            result = await self.adapter.get_my_status(self.event('我的网络怎么样', sender='222222'))
        data = json.loads(result)
        self.assertEqual(data['subject_qq'], '222222')
        self.assertIn('没有可读取的新鲜状态', data['detail'])
        self.assertIn('不能据此判断本人离线', data['detail'])

    async def test_transport_failure_is_not_identity_failure(self):
        with patch('urllib.request.urlopen', side_effect=OSError('offline')):
            result = await self.adapter.get_my_status(self.event('我的网络怎么样', sender='222222'))
        self.assertIn('状态服务暂时连接不上', result)

    def test_validation_only_removes_nonplaying_music(self):
        payload={'devices':[{'device':'phone','app':'editor','app_details':{'label':'Editor'},
                 'music':{'title':'Old song','playing':False},'device_state':{'charging':True},
                 'network':{'validated':True},'audio':{'media_muted':False}}]}
        result=self.m.validate_signals(payload)
        self.assertEqual(set(result['devices'][0]),{'device','app','app_details','device_state','network','audio'})
        self.assertIn('music',payload['devices'][0])  # No mutation of source.

    def test_false_and_numeric_facts_are_preserved(self):
        payload={'devices':[{'device':'phone','device_state':{'locked':True,'charging':False,'battery_percent':80}}]}
        result=self.m.validate_signals(payload)
        self.assertEqual(result['devices'][0]['device_state'],payload['devices'][0]['device_state'])
        payload['devices'][0]['device_state']={'locked':False,'charging':False}
        self.assertEqual(self.m.validate_signals(payload)['devices'],payload['devices'])

    async def test_general_empty_reply_does_not_offer_technical_checklist(self):
        with patch('urllib.request.urlopen',return_value=io.BytesIO(b'{"devices":[]}')):
            result=await self.adapter.get_my_status(self.event('我在干什么',sender='222222'))
        self.assertIn('没有可读取的新鲜状态',json.loads(result)['detail'])
        self.assertNotIn('检查 App',result)
