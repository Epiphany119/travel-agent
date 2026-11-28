package com.travel.module.itinerary.biz.planning.provider;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 第一阶段内置城市物价基准占位实现。
 * 覆盖国内超一线、新一线/省会、热门度假城市与海外目的地。
 */
@Component
public class LocalCityPriceBenchmarkProvider implements CityPriceBenchmarkProvider {

    private static final Map<String, CityPriceProfile> PROFILES = new LinkedHashMap<>();

    static {
        // 超一线 & 高消费核心城市
        put("北京", "TIER_1", "超一线城市", 520, 180, 95, 45, 950, "核心商圈住宿偏高，地铁覆盖广且票价亲民");
        put("上海", "TIER_1", "超一线城市", 580, 210, 110, 50, 980, "住宿与餐饮消费全国领先，建议优先选地铁沿线酒店");
        put("深圳", "TIER_1", "超一线城市", 500, 185, 90, 45, 1050, "商旅酒店为主，周末滨海区域价格上浮明显");
        put("广州", "TIER_1", "超一线城市", 460, 165, 85, 40, 960, "餐饮性价比高，早茶与夜宵选择丰富");
        put("香港", "OVERSEAS", "高消费目的地", 980, 320, 160, 95, 1600, "住宿面积紧凑且价格高，八达通市内通勤便捷");
        put("澳门", "OVERSEAS", "高消费目的地", 920, 290, 120, 60, 1500, "酒店周末溢价显著，发财车可节省部分市内交通");

        // 热门新一线 & 省会旅游城市
        put("杭州", "TIER_2", "新一线热门城市", 420, 155, 80, 40, 760, "西湖周边节假日房源紧张，灵隐/西溪片区打车较频繁");
        put("苏州", "TIER_2", "新一线热门城市", 380, 145, 90, 35, 720, "园林门票集中，平江路/观前街步行可达度高");
        put("南京", "TIER_2", "新一线热门城市", 370, 140, 75, 35, 720, "多数历史展馆免费预约，餐饮与地铁性价比高");
        put("成都", "TIER_2", "新一线热门城市", 350, 135, 70, 35, 880, "本地美食性价比极高，市区景点地铁衔接顺畅");
        put("重庆", "TIER_2", "新一线热门城市", 330, 125, 65, 35, 860, "地形起伏大，轨道交通+打车组合最省力");
        put("西安", "TIER_2", "历史文化名城", 340, 130, 105, 35, 840, "兵马俑/华清宫等景区门票与郊区通勤占比较高");
        put("厦门", "TIER_2", "滨海旅游城市", 410, 150, 80, 40, 900, "鼓浪屿船票及海景民宿旺季溢价明显");
        put("青岛", "TIER_2", "滨海旅游城市", 390, 145, 75, 40, 820, "暑期海滨住宿涨幅较大，淡季性价比极佳");
        put("长沙", "TIER_2", "热门美食城市", 320, 130, 50, 35, 780, "核心景区多免费，消费主要集中在住宿与特色餐饮");
        put("武汉", "TIER_2", "新一线城市", 330, 125, 65, 35, 760, "跨江景点距离较远，建议按武昌/汉口分区串联");

        // 热门度假 & 特色旅游城市
        put("三亚", "RESORT", "热门海岛度假地", 620, 195, 130, 65, 1450, "海湾度假酒店与水上项目占比高，冬季为绝对旺季");
        put("大理", "TIER_3", "特色慢旅城市", 360, 125, 60, 55, 1150, "环洱海租车/包车交通占比略高，古城周边餐饮适中");
        put("丽江", "TIER_3", "特色慢旅城市", 340, 120, 110, 50, 1180, "玉龙雪山索道及进山费较高，古城客栈选择丰富");
        put("桂林", "TIER_3", "山水旅游城市", 310, 115, 115, 50, 920, "漓江游船与阳朔景区门票占比高，住宿选择亲民");
        put("拉萨", "TIER_3", "高原特色目的地", 380, 135, 120, 60, 1650, "大交通成本较高，高海拔行程建议放慢节奏");
        put("哈尔滨", "TIER_2", "季节性热门城市", 430, 140, 120, 45, 1100, "冬季冰雪季住宿与门票溢价明显，夏秋性价比高");

        // 海外热门目的地
        put("东京", "OVERSEAS", "海外热门城市", 950, 320, 140, 95, 3200, "轨道交通发达但单程票价较高，建议按片区集中游览");
        put("大阪", "OVERSEAS", "海外热门城市", 820, 280, 135, 85, 2900, "美食丰富，环球影城等主题乐园门票预算需单独预留");
        put("京都", "OVERSEAS", "海外文化名城", 860, 270, 110, 80, 3000, "樱花季与红叶季酒店溢价可达 1.5 倍以上");
        put("新加坡", "OVERSEAS", "海外热门城市", 1100, 330, 170, 85, 2800, "酒店价格较高，熟食中心餐饮可有效平衡预算");
        put("曼谷", "OVERSEAS", "东南亚热门城市", 420, 160, 95, 55, 1950, "酒店与餐饮性价比高，主要成本在往返国际机票");
        put("首尔", "OVERSEAS", "海外热门城市", 720, 240, 100, 70, 2100, "商圈集中，地铁换乘便利，美妆购物预算可按需单列");
    }

    private static void put(String city, String tier, String label, double hotel, double meal,
                            double ticket, double transit, double transport, String note) {
        PROFILES.put(city, new CityPriceProfile(city, tier, label, hotel, meal, ticket, transit, transport, note));
    }

    @Override
    public CityPriceProfile resolve(String destination) {
        if (destination != null && !destination.isBlank()) {
            String normalized = destination.trim();
            for (Map.Entry<String, CityPriceProfile> entry : PROFILES.entrySet()) {
                if (normalized.contains(entry.getKey()) || entry.getKey().contains(normalized)) {
                    return entry.getValue();
                }
            }
        }
        String cityName = (destination == null || destination.isBlank()) ? "通用目的地" : destination.trim();
        return new CityPriceProfile(
                cityName,
                "TIER_2",
                "常规旅游城市（基准估算）",
                360,
                135,
                80,
                40,
                850,
                "当前按国内常规旅游城市物价基准估算，后续接入实时平台后将自动校准"
        );
    }

    @Override
    public double seasonalMultiplier(int month) {
        return switch (month) {
            case 1, 2, 7, 8, 10 -> 1.18; // 寒暑假/春节/国庆旺季
            case 4, 5, 9 -> 1.08;        // 春秋平旺季
            default -> 0.96;             // 淡季
        };
    }
}
