"""One explicit field catalog for native clients, web and audience projection."""
import copy
from typing import get_args

CATALOG = []
KEYS = set()
GROUPS = {'app':'应用','app_details':'应用详情','music':'音乐与媒体','device_state':'设备与电源','network':'网络','audio':'声音','resources':'系统资源','collector_health':'采集诊断','presence':'活跃状态','manual_status':'手动状态','display':'显示环境','usage_summary':'WhatPulse 统计'}
LABELS = dict(x.split('=',1) for x in '''app=前台应用标识
device_label=设备名称
label=应用名称
active_for_ms=当前前台片段时长
foreground=是否前台窗口
input_idle=是否缺少近期输入
z_order=窗口层级
always_on_top=窗口置顶
maximized=窗口最大化
fullscreen=全屏状态
title=媒体标题
artist=歌手或作者
album=专辑
player=播放器标识
playing=正在播放
playback_status=播放状态
position_ms=播放进度
duration_ms=媒体时长
playback_rate=播放速率
track_id=曲目标识
lyric=当前歌词
lyric_window=歌词时间窗口
lyric_status=歌词匹配状态
media_kind=媒体类型
position_known=进度可用性
battery_present=是否有电池
ac_connected=外接电源
battery_percent=电量
charging=充电状态
screen_on=屏幕交互状态
locked=锁屏状态
power_save=省电模式
battery_temperature_c=电池温度
battery_voltage_mv=电池电压
charge_source=充电来源
battery_health=电池健康状态
connected=网络连接
validated=系统联网验证
metered=计费网络
transports=网络类型
default_output_form=默认音频输出类型
active_output_sessions=活跃音频会话数
music_volume_percent=媒体音量
media_muted=媒体静音
ringer_mode=响铃模式
interruption_filter=免打扰模式
connected_output_types=已连接音频设备类型
available_memory_mib=可用内存
total_memory_mib=总内存
low_memory=低内存状态
available_storage_mib=可用存储
total_storage_mib=总存储
cpu_percent=CPU 使用率
thermal_status=系统热状态
version=采集器版本
sdk=Android SDK 版本
notification_access=通知访问授权状态
usage_access=使用情况授权状态
music_directory_access=音乐目录授权状态
battery_optimization_exempt=电池优化豁免状态
state=活跃状态
idle_seconds=缺少输入时长
text=手动状态文字
expires_at=手动状态有效期
monitor_count=显示器数量
remote_session=远程会话
source=统计来源
scope=统计范围
sampled_at=统计采样时间
keys=按键次数
clicks=点击次数
scrolls=滚动次数
download=下载字节数
upload=上传字节数
uptime=运行时长
keys_per_second=每秒按键
clicks_per_second=每秒点击
download_bytes_per_second=下载速率
upload_bytes_per_second=上传速率
sample_seconds=速率采样窗口'''.splitlines())

def configure(event_model):
    CATALOG.clear(); KEYS.clear()
    CATALOG.append(dict(key='device_label',label='设备名称',group='设备名称',description='设备名称可能包含个人姓名；关闭时显示通用设备编号。'))
    CATALOG.append(dict(key='app',label=LABELS['app'],group='应用',description='应用包名或进程标识。'))
    for group in GROUPS:
        if group == 'app': continue
        field = event_model.model_fields[group]
        model = next((x for x in get_args(field.annotation) if hasattr(x, 'model_fields')), None)
        if model is None: continue
        for key in model.model_fields:
            if (group,key) in {('music','playing'),('music','playback_status'),('music','position_known'),('manual_status','expires_at')}: continue
            CATALOG.append(dict(key=group+'.'+key,label=LABELS.get(key,key),group=GROUPS[group],description=('仅播放中展示；附带播放状态，进度附带其准确性。' if group=='music' else '附带状态有效期。' if group=='manual_status' else '仅展示设备已采集并上传的内容。')))
    for key,label,description in [('summary','屏幕与解锁汇总','最近 30 天每日屏幕时长、解锁次数；会反映日常使用习惯。'),('apps','历史应用排行','最近 30 天允许分享的应用名称、包名、使用时长和次数。'),('hourly','每小时屏幕分布','最近 30 天的小时分布，会反映作息。')]:
        CATALOG.append(dict(key='usage_history.'+key,label=label,group='手机历史统计',description=description))
    KEYS.update(x['key'] for x in CATALOG)

def canonical(fields):
    """Accept only exact catalog keys; no aliases or implicit grants."""
    return sorted(set(fields) & KEYS)

def project_device(item, fields):
    selected=set(fields); result={k:item[k] for k in ('device','age_seconds','expires_in') if k in item}
    if 'device_label' in selected and 'device_label' in item:result['device_label']=item['device_label']
    for key in selected:
        if key in ('app',):
            if item.get(key) is not None:result[key]=item[key]
        elif '.' in key:
            parent,leaf=key.split('.',1)
            if parent=='usage_history':continue
            if isinstance(item.get(parent),dict) and item[parent].get(leaf) is not None:
                result.setdefault(parent,{})[leaf]=copy.deepcopy(item[parent][leaf])
    if 'music' in result:
        result['music']['playing']=True # structural fact: only active media reaches this projection
        result['music']['playback_status']='playing'
        result['music']['position_known']=bool(item['music'].get('position_known',False)) if 'position_ms' in result['music'] else False
    if 'manual_status' in result and item.get('manual_status',{}).get('expires_at'):
        result['manual_status']['expires_at']=item['manual_status']['expires_at']
    return result

def project_history(device, fields):
    selected=set(fields)
    if not selected.intersection({'usage_history.summary','usage_history.apps','usage_history.hourly'}):return None
    result={k:copy.deepcopy(device[k]) for k in ('device','source','timezone','sampled_at','first_event_at','received_at') if k in device}
    if 'device_label' in selected and 'device_label' in device:result['device_label']=device['device_label']
    result['days']=[]
    for day in device.get('days',[]):
        row={k:day[k] for k in ('date','known_ms') if k in day}
        if 'usage_history.summary' in selected:row.update({k:day[k] for k in ('screen_ms','unlocked_ms','unlocks') if k in day})
        if 'usage_history.apps' in selected:row.update({k:copy.deepcopy(day[k]) for k in ('apps','apps_truncated') if k in day})
        if 'usage_history.hourly' in selected:row['hourly_screen_ms']=copy.deepcopy(day.get('hourly_screen_ms',[]))
        result['days'].append(row)
    return result
