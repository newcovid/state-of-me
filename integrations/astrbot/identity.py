"""Conservative subject resolution from the actual message, not LLM arguments."""
import re


def resolve_subject(text, sender, owner, mentions, aliases=()):
    text = re.sub(r'\[CQ:at,[^]]+\]', '', text).strip()
    targets = set(mentions)
    targets.update(re.findall(r'(?<!\d)\d{5,12}(?!\d)', text))
    if '群主' in text:
        targets.add('group_owner')
    if '设备主人' in text or any(alias and alias in text for alias in aliases):
        if not owner.isdigit():
            return None, '请明确 @具体成员或询问自己的状态。'
        targets.add(owner)
    # "我想知道群主..." names the group owner, not two subjects.
    if re.search(r'我(?:现在|正在|今天)?(?:在|正|听|用|的(?:状态|手机|电脑|设备|歌曲|音乐|电量|电池|网络|声音|音量|存储|内存|权限|采集|应用|手机温度))', text) or re.search(r'我(?:和|与|、)', text):
        targets.add(sender)
    if len(targets) > 1:
        return None, '请一次明确询问一个人的状态（本人、群主或 @具体成员）。'
    if not targets:
        return None, '请说明要查询谁：例如“我在干什么”“群主在干什么”或 @具体成员。'
    return targets.pop(), None
