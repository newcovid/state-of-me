import asyncio
import importlib.util
import io
import json
import sys
import types
import unittest
from unittest.mock import patch, AsyncMock


class AdapterTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        event_module=types.ModuleType('astrbot.api.event')
        event_module.filter=types.SimpleNamespace(on_llm_request=lambda **k:lambda f:f, llm_tool=lambda **k:lambda f:f)
        event_module.AstrMessageEvent=object
        star_module=types.ModuleType('astrbot.api.star')
        star_module.Context=object
        star_module.Star=object
        star_module.register=lambda *a:lambda c:c
        with patch.dict(sys.modules,{'astrbot.api.event':event_module,'astrbot.api.star':star_module}):
            spec=importlib.util.spec_from_file_location('integrations.astrbot.adapter_test_subject',__file__.replace('test_adapter.py','main.py'))
            self.m=importlib.util.module_from_spec(spec);spec.loader.exec_module(self.m)
        self.m.configuration=lambda:{'url':'https://example.com','groups':{'123456':{'owner_qq':'222222','token':'x'*32}}}
        self.adapter=object.__new__(self.m.StateOfMe)

    def event(self,text,sender='111111',members=None):
        api=types.SimpleNamespace(call_action=AsyncMock(return_value=members))
        return types.SimpleNamespace(message_str=text,get_group_id=lambda:'123456',get_platform_name=lambda:'aiocqhttp',get_sender_id=lambda:sender,get_self_id=lambda:'999999',get_messages=lambda:[],bot=types.SimpleNamespace(api=api))

    async def test_other_self_never_fetches_owner(self):
        with patch('urllib.request.urlopen') as network:
            r=await self.adapter.get_my_status(self.event('我在干什么'))
            self.assertEqual(json.loads(r)['status'],'unbound');network.assert_not_called()

    async def test_live_owner_and_subject(self):
        event=self.event('群主在干什么',members=[{'user_id':222222,'role':'owner'}])
        with patch('urllib.request.urlopen',return_value=io.BytesIO(b'{"devices":[]}')):
            r=await self.adapter.get_my_status(event)
        self.assertIn('"subject_qq": "222222"',r)
        event.bot.api.call_action.assert_awaited_once_with('get_group_member_list',group_id=123456,no_cache=True)

    async def test_transferred_owner_and_api_failure(self):
        e=self.event('群主在干什么',members=[{'user_id':333333,'role':'owner'}])
        with patch('urllib.request.urlopen') as network:
            r=await self.adapter.get_my_status(e);self.assertEqual(json.loads(r)['subject_qq'],'333333');network.assert_not_called()
            e.bot.api.call_action.side_effect=RuntimeError('offline')
            r=await self.adapter.get_my_status(e);self.assertIn('不能确认',r);network.assert_not_called()
