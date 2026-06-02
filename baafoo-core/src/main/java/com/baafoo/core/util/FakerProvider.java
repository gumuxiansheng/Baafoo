package com.baafoo.core.util;

import java.security.SecureRandom;
import java.util.Random;

/**
 * Lightweight faker data provider — zero external dependencies, Java 8 compatible.
 *
 * <p>Supported functions (invoked via {@code {{faker.xxx}}} in response body templates):
 * <ul>
 *   <li>{@code {{faker.phone}}} / {@code {{faker.phoneNumber}}} — mobile phone number</li>
 *   <li>{@code {{faker.email}}} / {@code {{faker.emailAddress}}} — random email</li>
 *   <li>{@code {{faker.name}}} / {@code {{faker.fullName}}} — Chinese full name</li>
 *   <li>{@code {{faker.firstName}}} — given name</li>
 *   <li>{@code {{faker.lastName}}} — family name</li>
 *   <li>{@code {{faker.address}}} / {@code {{faker.fullAddress}}} — full street address</li>
 *   <li>{@code {{faker.city}}} — city name</li>
 *   <li>{@code {{faker.province}}} — province name</li>
 *   <li>{@code {{faker.zipCode}}} / {@code {{faker.postcode}}} — postal code</li>
 *   <li>{@code {{faker.idCard}}} / {@code {{faker.idNumber}}} — 18-digit ID number</li>
 *   <li>{@code {{faker.company}}} / {@code {{faker.companyName}}} — company name</li>
 *   <li>{@code {{faker.url}}} / {@code {{faker.website}}} — random URL</li>
 *   <li>{@code {{faker.ip}}} / {@code {{faker.ipv4}}} — IPv4 address</li>
 *   <li>{@code {{faker.ipv6}}} — IPv6 address</li>
 *   <li>{@code {{faker.uuid}}} — UUID string</li>
 *   <li>{@code {{faker.timestamp}}} — current epoch millis</li>
 *   <li>{@code {{faker.date}}} — date string yyyy-MM-dd</li>
 *   <li>{@code {{faker.dateTime}}} — datetime string yyyy-MM-dd HH:mm:ss</li>
 *   <li>{@code {{faker.int}}} / {@code {{faker.integer}}} — random int 0-9999</li>
 *   <li>{@code {{faker.int.min.max}}} — random int between min and max (inclusive)</li>
 *   <li>{@code {{faker.float}}} / {@code {{faker.decimal}}} — random float 0-10000 with 2 decimals</li>
 *   <li>{@code {{faker.boolean}}} — "true" or "false"</li>
 *   <li>{@code {{faker.hex}}} — 8-char hex string</li>
 *   <li>{@code {{faker.alphaNumeric}}} — 8-char alphanumeric string</li>
 *   <li>{@code {{faker.locale}}} — random locale code (zh_CN, en_US, etc.)</li>
 *   <li>{@code {{faker.userAgent}}} — random browser user-agent</li>
 *   <li>{@code {{faker.statusCode}}} — common HTTP status code (200/404/500 etc.)</li>
 *   <li>{@code {{faker.color}}} / {@code {{faker.hexColor}}} — hex color like #A3F2C1</li>
 * </ul>
 * </p>
 */
public class FakerProvider {

    private static final Random RND = new SecureRandom();

    // --- Chinese name data ---
    private static final String[] LAST_NAMES = {
            "王", "李", "张", "刘", "陈", "杨", "赵", "黄", "周", "吴",
            "徐", "孙", "胡", "朱", "高", "林", "何", "郭", "马", "罗",
            "梁", "宋", "郑", "谢", "韩", "唐", "冯", "于", "董", "萧",
            "程", "曹", "袁", "邓", "许", "傅", "沈", "曾", "彭", "吕",
            "苏", "卢", "蒋", "蔡", "贾", "丁", "魏", "薛", "叶", "阎",
            "余", "潘", "杜", "戴", "夏", "钟", "汪", "田", "任", "姜",
            "范", "方", "石", "姚", "谭", "廖", "邹", "熊", "金", "陆",
            "郝", "孔", "白", "崔", "康", "毛", "邱", "秦", "江", "史",
            "顾", "侯", "邵", "孟", "龙", "万", "段", "雷", "钱", "汤"
    };

    private static final String[] FIRST_NAMES = {
            "伟", "芳", "娜", "秀英", "敏", "静", "丽", "强", "磊", "军",
            "洋", "勇", "艳", "杰", "娟", "涛", "明", "超", "秀兰", "霞",
            "平", "刚", "桂英", "文", "华", "慧", "建华", "建国", "建军", "玉兰",
            "飞", "鹏", "雪", "梅", "莉", "婷", "欣", "瑶", "佳", "颖",
            "博", "宇", "浩", "然", "子涵", "梓涵", "一诺", "欣怡", "思远", "天宇",
            "晨曦", "雨泽", "梓轩", "子墨", "浩然", "博文", "思源", "俊杰", "嘉豪", "宇航",
            "诗涵", "语嫣", "若曦", "紫萱", "梦瑶", "雅琴", "晓雯", "丽华", "美玲", "小红"
    };

    // --- City / Province data ---
    private static final String[] PROVINCES = {
            "北京市", "上海市", "天津市", "重庆市",
            "广东省", "江苏省", "浙江省", "山东省", "河南省", "四川省",
            "湖北省", "湖南省", "河北省", "福建省", "安徽省", "辽宁省",
            "陕西省", "江西省", "云南省", "广西壮族自治区", "山西省",
            "黑龙江省", "吉林省", "贵州省", "甘肃省", "内蒙古自治区",
            "新疆维吾尔自治区", "西藏自治区", "宁夏回族自治区", "海南省", "青海省"
    };

    private static final String[][] CITIES = {
            {"北京"}, {"上海"}, {"天津"}, {"重庆"},
            {"广州", "深圳", "东莞", "佛山", "珠海", "惠州", "中山", "汕头"},
            {"南京", "苏州", "无锡", "常州", "徐州", "南通", "扬州", "盐城"},
            {"杭州", "宁波", "温州", "绍兴", "嘉兴", "金华", "台州", "湖州"},
            {"济南", "青岛", "烟台", "潍坊", "临沂", "淄博", "威海", "济宁"},
            {"郑州", "洛阳", "开封", "南阳", "安阳", "新乡", "许昌", "平顶山"},
            {"成都", "绵阳", "德阳", "宜宾", "南充", "泸州", "达州", "乐山"},
            {"武汉", "宜昌", "襄阳", "荆州", "黄石", "十堰", "孝感", "荆门"},
            {"长沙", "株洲", "湘潭", "衡阳", "岳阳", "常德", "益阳", "郴州"},
            {"石家庄", "唐山", "保定", "邯郸", "秦皇岛", "廊坊", "沧州", "承德"},
            {"福州", "厦门", "泉州", "漳州", "莆田", "龙岩", "三明", "南平"},
            {"合肥", "芜湖", "蚌埠", "淮南", "马鞍山", "安庆", "宿州", "阜阳"},
            {"沈阳", "大连", "鞍山", "抚顺", "锦州", "营口", "丹东", "葫芦岛"},
            {"西安", "宝鸡", "咸阳", "渭南", "汉中", "延安", "榆林", "安康"},
            {"南昌", "九江", "赣州", "吉安", "上饶", "抚州", "宜春", "景德镇"},
            {"昆明", "大理", "丽江", "曲靖", "玉溪", "昭通", "保山", "普洱"},
            {"南宁", "柳州", "桂林", "北海", "梧州", "玉林", "钦州", "百色"},
            {"太原", "大同", "运城", "临汾", "长治", "晋中", "晋城", "忻州"},
            {"哈尔滨", "齐齐哈尔", "牡丹江", "佳木斯", "大庆", "绥化", "鸡西", "鹤岗"},
            {"长春", "吉林", "四平", "延边", "通化", "松原", "白城", "辽源"},
            {"贵阳", "遵义", "六盘水", "安顺", "毕节", "铜仁", "黔南", "黔东南"},
            {"兰州", "天水", "白银", "庆阳", "平凉", "酒泉", "张掖", "武威"},
            {"呼和浩特", "包头", "鄂尔多斯", "赤峰", "通辽", "呼伦贝尔", "巴彦淖尔", "乌兰察布"},
            {"乌鲁木齐", "克拉玛依", "吐鲁番", "哈密", "昌吉", "伊犁", "阿克苏", "喀什"},
            {"拉萨", "日喀则", "昌都", "林芝", "山南", "那曲"},
            {"银川", "石嘴山", "吴忠", "固原", "中卫"},
            {"海口", "三亚", "儋州", "琼海", "万宁", "文昌"},
            {"西宁", "海东", "海西", "海南州", "海北州"}
    };

    // --- Street / road names ---
    private static final String[] STREETS = {
            "中山路", "人民路", "解放路", "建设路", "和平路", "文化路", "长安路", "长江路",
            "北京路", "上海路", "南京路", "黄河路", "幸福路", "团结路", "民主路", "光明路",
            "新华路", "胜利路", "朝阳路", "学府路", "科技路", "创新路", "滨海路", "望江路",
            "凤凰路", "龙腾路", "天府路", "锦绣路", "花园路", "翠竹路"
    };

    private static final String[] COMPANIES_PREFIX = {
            "华", "中", "恒", "鑫", "瑞", "博", "达", "创", "联", "盛",
            "嘉", "宏", "益", "润", "飞", "云", "智", "诚", "泰", "信"
    };

    private static final String[] COMPANIES_SUFFIX = {
            "科技有限公司", "信息技术有限公司", "网络科技有限公司", "数据服务有限公司",
            "软件开发有限公司", "电子商务有限公司", "智能科技有限公司", "云计算有限公司",
            "传媒有限公司", "咨询有限公司", "投资管理有限公司", "贸易有限公司"
    };

    private static final String[] EMAIL_DOMAINS = {
            "qq.com", "163.com", "126.com", "gmail.com", "outlook.com",
            "hotmail.com", "foxmail.com", "sina.com", "yeah.net", "icloud.com"
    };

    private static final String[] LOCALES = {
            "zh_CN", "en_US", "en_GB", "ja_JP", "ko_KR", "fr_FR", "de_DE",
            "es_ES", "pt_BR", "ru_RU", "it_IT", "ar_SA", "th_TH", "vi_VN"
    };

    private static final String[] USER_AGENTS = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    };

    private static final String[] HTTP_STATUS_CODES = {
            "200", "201", "204", "301", "302", "304", "400", "401", "403",
            "404", "405", "408", "409", "422", "429", "500", "502", "503", "504"
    };

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();
    private static final char[] ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private FakerProvider() {
        // utility class
    }

    /**
     * Resolve a faker function name to a random value.
     *
     * @param functionName function name without the "faker." prefix, e.g. "phone", "int.1.100"
     * @return generated value string
     */
    public static String resolve(String functionName) {
        if (functionName == null || functionName.isEmpty()) {
            return "";
        }

        // Handle parameterized functions like "int.1.100"
        if (functionName.startsWith("int.") || functionName.startsWith("integer.")) {
            return resolveIntRange(functionName);
        }

        switch (functionName) {
            // Phone
            case "phone":
            case "phoneNumber":
                return randomPhone();

            // Email
            case "email":
            case "emailAddress":
                return randomEmail();

            // Name
            case "name":
            case "fullName":
                return randomFullName();
            case "firstName":
                return randomFirstName();
            case "lastName":
                return randomLastName();

            // Address
            case "address":
            case "fullAddress":
                return randomFullAddress();
            case "city":
                return randomCity();
            case "province":
                return randomProvince();
            case "zipCode":
            case "postcode":
                return randomZipCode();
            case "street":
            case "streetAddress":
                return randomStreetAddress();

            // ID
            case "idCard":
            case "idNumber":
                return randomIdCard();

            // Company
            case "company":
            case "companyName":
                return randomCompany();

            // Network
            case "url":
            case "website":
                return randomUrl();
            case "ip":
            case "ipv4":
                return randomIpv4();
            case "ipv6":
                return randomIpv6();
            case "mac":
            case "macAddress":
                return randomMacAddress();

            // Identity / Time
            case "uuid":
                return randomUuid();
            case "timestamp":
                return String.valueOf(System.currentTimeMillis());
            case "date":
                return randomDate();
            case "dateTime":
                return randomDateTime();

            // Number
            case "int":
            case "integer":
                return String.valueOf(RND.nextInt(10000));
            case "float":
            case "decimal":
                return String.format("%.2f", RND.nextDouble() * 10000);
            case "boolean":
                return String.valueOf(RND.nextBoolean());

            // String
            case "hex":
                return randomHex(8);
            case "alphaNumeric":
                return randomAlphaNumeric(8);
            case "string":
                return randomAlphaNumeric(16);

            // Misc
            case "locale":
                return LOCALES[RND.nextInt(LOCALES.length)];
            case "userAgent":
                return USER_AGENTS[RND.nextInt(USER_AGENTS.length)];
            case "statusCode":
                return HTTP_STATUS_CODES[RND.nextInt(HTTP_STATUS_CODES.length)];
            case "color":
            case "hexColor":
                return randomHexColor();

            default:
                return "{{faker." + functionName + "}}";
        }
    }

    // --- Generation methods ---

    private static String randomPhone() {
        // Chinese mobile prefixes
        String[] prefixes = {"130", "131", "132", "133", "134", "135", "136", "137", "138", "139",
                "150", "151", "152", "153", "155", "156", "157", "158", "159",
                "170", "176", "177", "178", "180", "181", "182", "183", "184", "185", "186", "187", "188", "189",
                "191", "198", "199"};
        return prefixes[RND.nextInt(prefixes.length)] + randomDigits(8);
    }

    private static String randomEmail() {
        String user = randomAlphaNumeric(4 + RND.nextInt(8)).toLowerCase();
        String domain = EMAIL_DOMAINS[RND.nextInt(EMAIL_DOMAINS.length)];
        return user + "@" + domain;
    }

    private static String randomFullName() {
        return randomLastName() + randomFirstName();
    }

    private static String randomFirstName() {
        return FIRST_NAMES[RND.nextInt(FIRST_NAMES.length)];
    }

    private static String randomLastName() {
        return LAST_NAMES[RND.nextInt(LAST_NAMES.length)];
    }

    private static String randomProvince() {
        return PROVINCES[RND.nextInt(PROVINCES.length)];
    }

    private static String randomCity() {
        int idx = RND.nextInt(CITIES.length);
        String[] provinceCities = CITIES[idx];
        return provinceCities[RND.nextInt(provinceCities.length)];
    }

    private static String randomStreetAddress() {
        String street = STREETS[RND.nextInt(STREETS.length)];
        String number = String.valueOf(1 + RND.nextInt(999));
        return street + number + "号";
    }

    private static String randomFullAddress() {
        return randomProvince() + randomCity() + randomStreetAddress();
    }

    private static String randomZipCode() {
        return randomDigits(6);
    }

    private static String randomIdCard() {
        // Area code (6 digits) + birth date (8 digits) + sequence (3 digits) + check digit
        String areaCode = randomDigits(6);
        int year = 1960 + RND.nextInt(50);
        int month = 1 + RND.nextInt(12);
        int day = 1 + RND.nextInt(28);
        String birth = String.format("%04d%02d%02d", year, month, day);
        String seq = String.format("%03d", 1 + RND.nextInt(999));
        String base = areaCode + birth + seq;
        // Check digit
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        char[] checkChars = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (base.charAt(i) - '0') * weights[i];
        }
        return base + checkChars[sum % 11];
    }

    private static String randomCompany() {
        return COMPANIES_PREFIX[RND.nextInt(COMPANIES_PREFIX.length)]
                + COMPANIES_PREFIX[RND.nextInt(COMPANIES_PREFIX.length)]
                + COMPANIES_SUFFIX[RND.nextInt(COMPANIES_SUFFIX.length)];
    }

    private static String randomUrl() {
        String[] protocols = {"http", "https"};
        String[] domains = {"example", "mock", "test", "demo", "api", "service"};
        String[] tlds = {".com", ".cn", ".io", ".net", ".org"};
        return protocols[RND.nextInt(protocols.length)] + "://"
                + domains[RND.nextInt(domains.length)]
                + randomDigits(2)
                + tlds[RND.nextInt(tlds.length)];
    }

    private static String randomIpv4() {
        return (10 + RND.nextInt(240)) + "."
                + RND.nextInt(256) + "."
                + RND.nextInt(256) + "."
                + (1 + RND.nextInt(254));
    }

    private static String randomIpv6() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0) sb.append(":");
            sb.append(String.format("%04x", RND.nextInt(65536)));
        }
        return sb.toString();
    }

    private static String randomMacAddress() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(":");
            sb.append(String.format("%02X", RND.nextInt(256)));
        }
        return sb.toString();
    }

    private static String randomUuid() {
        return java.util.UUID.randomUUID().toString();
    }

    private static String randomDate() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
        return sdf.format(new java.util.Date());
    }

    private static String randomDateTime() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(new java.util.Date());
    }

    private static String randomHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(HEX_CHARS[RND.nextInt(16)]);
        }
        return sb.toString();
    }

    private static String randomHexColor() {
        return "#" + randomHex(6).toUpperCase();
    }

    private static String randomAlphaNumeric(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUMERIC[RND.nextInt(ALPHANUMERIC.length)]);
        }
        return sb.toString();
    }

    private static String randomDigits(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RND.nextInt(10));
        }
        return sb.toString();
    }

    /**
     * Resolve parameterized integer range: "int.min.max" or "integer.min.max"
     */
    private static String resolveIntRange(String functionName) {
        // Strip "int." or "integer." prefix
        String params = functionName.startsWith("integer.") ? functionName.substring(8) : functionName.substring(4);
        String[] parts = params.split("\\.");
        if (parts.length >= 2) {
            try {
                int min = Integer.parseInt(parts[0]);
                int max = Integer.parseInt(parts[1]);
                if (min > max) { int t = min; min = max; max = t; }
                return String.valueOf(min + RND.nextInt(max - min + 1));
            } catch (NumberFormatException e) {
                // fall through to default
            }
        }
        return String.valueOf(RND.nextInt(10000));
    }
}
