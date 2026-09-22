package com.szech.walls.pipeline

/**
 * 默认节点源，对应 Windows 版 tools/fetch_nodes.py 的 SOURCES。
 * 末尾带 #type=list 的表示这是一个「订阅合集页」，需要展开里面出现的订阅链接。
 * 用户可以在「设置」里增删，改动保存在本机。
 */
object DefaultSources {

    val LIST: List<String> = listOf(
        // Clash / YAML 格式源
        "https://raw.githubusercontent.com/ripaojiedian/freenode/main/clash",
        "https://raw.githubusercontent.com/ripaojiedian/freenode/main/sub",
        "https://raw.githubusercontent.com/zhangkaiitugithub/passcro/main/speednodes.yaml",
        "https://raw.githubusercontent.com/shaoyouvip/free/refs/heads/main/all.yaml",
        "https://raw.githubusercontent.com/anaer/Sub/main/clash.yaml",
        "https://raw.githubusercontent.com/learnhard-cn/free_proxy_ss/main/clash/clash.provider.yaml",
        // Base64 / 通用列表源
        "https://raw.githubusercontent.com/Surfboardv2ray/TGParse/main/python/hy2",
        "https://raw.githubusercontent.com/Surfboardv2ray/TGParse/main/python/hysteria2",
        "https://raw.githubusercontent.com/freefq/free/master/v2",
        "https://raw.githubusercontent.com/aiboboxx/v2rayfree/main/v2",
        "https://raw.githubusercontent.com/Pawdroid/Free-servers/main/sub",
        "https://raw.githubusercontent.com/mfuu/v2ray/master/v2ray",
        "https://raw.githubusercontent.com/mahdibland/ShadowsocksAggregator/master/sub/sub_merge.txt",
        "https://raw.githubusercontent.com/mahdibland/ShadowsocksAggregator/master/Eternity.yml",
        "https://raw.githubusercontent.com/ermaozi/get_subscribe/main/subscribe/v2ray.txt",
        "https://raw.githubusercontent.com/ermaozi/get_subscribe/main/subscribe/clash.yml",
        "https://raw.githubusercontent.com/peasoft/NoMoreWalls/master/list.yml",
        "https://raw.githubusercontent.com/peasoft/NoMoreWalls/master/list.txt",
        "https://raw.githubusercontent.com/vveg26/get_proxy/main/dist/clash.config.yaml",
        // 订阅合集页（自动展开）
        "https://raw.githubusercontent.com/abshare/abshare.github.io/main/README.md#type=list"
    )

    /** 聚合页种子：专门收集订阅链接的页面，用来动态发现新源 */
    val SEED_PAGES: List<String> = listOf(
        "https://raw.githubusercontent.com/ermaozi/get_subscribe/main/README.md"
    )
}
