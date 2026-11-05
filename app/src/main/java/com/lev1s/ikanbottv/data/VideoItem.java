package com.lev1s.ikanbottv.data;

import java.util.List;

/**
 * 一个可点播条目（海报墙卡片 / 搜索结果行共用）。
 * 数据来自站点服务端 HTML 的两套模板解析（见 IkanHtmlParser）。
 */
public class VideoItem {
    /** 站点视频 ID（/play/{videoId}）；榜单里指向站内搜索的条目没有 ID，为 0 */
    public long videoId;
    public String title;
    /** 海报加载候选链（代理改写 + 回退，顺序即优先级）；无海报时为空列表 */
    public List<String> posterCandidates;
    /** 副标题（搜索结果的地区/演职员等），可为 null */
    public String subtitle;
    /** 榜单名次，0 = 无名次 */
    public int rank;
    /** 首页口碑榜里指向 /search?q= 的条目：点按时要打开的搜索词；普通条目为 null */
    public String searchQuery;

    /** 点播用的完整播放页 URL；无 videoId 时为 null */
    public String playUrl() {
        return videoId > 0 ? IkanDataSource.BASE + "/play/" + videoId : null;
    }
}
