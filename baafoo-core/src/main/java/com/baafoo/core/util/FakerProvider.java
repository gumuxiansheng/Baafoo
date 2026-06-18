package com.baafoo.core.util;

import java.security.SecureRandom;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
 *   <li>{@code {{faker.randomElement [a,b,c]}}} — pick a random element from the given array</li>
 *   <li>{@code {{faker.regexify 'pattern'}}} — generate a string matching the given regex</li>
 * </ul>
 * </p>
 *
 * <p>Seed support: when a rule has {@code fakerSeed} set, all faker functions for that
 * rule use a deterministic {@link Random} seeded with that value, so the same seed
 * produces the same sequence of values. Without a seed, a {@link SecureRandom} is used.</p>
 */
public class FakerProvider {

    /** Default random source (no seed) — cryptographically strong, non-deterministic. */
    private static final Random DEFAULT_RND = new SecureRandom();

    /** Thread-local random source; when seeded, replaces DEFAULT_RND for the current thread. */
    private static final ThreadLocal<Random> SEEDED_RND = new ThreadLocal<Random>();

    /** Cached compiled regex for randomElement argument parsing: [a,b,c] or a,b,c. */
    private static final Pattern RANDOM_ELEMENT_PATTERN =
            Pattern.compile("^\\s*\\[?\\s*(.*?)\\s*\\]?\\s*$");

    /** Cached compiled regex for regexify argument parsing: 'pattern' or "pattern" or pattern. */
    private static final Pattern REGEXIFY_QUOTE_PATTERN =
            Pattern.compile("^['\"]?(.*?)['\"]?$");

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
     * Obtain the random source for the current thread.
     *
     * <p>If a seed has been set via {@link #setSeed(Long)} for the current thread,
     * the seeded {@link Random} is returned; otherwise the default
     * {@link SecureRandom} is used.</p>
     */
    private static Random rnd() {
        Random seeded = SEEDED_RND.get();
        return seeded != null ? seeded : DEFAULT_RND;
    }

    /**
     * Set a deterministic seed for faker functions on the current thread.
     *
     * <p>When {@code seed} is non-null, all subsequent faker calls on this thread
     * use a {@link Random} seeded with that value, producing a deterministic
     * sequence. Pass {@code null} to clear the seed and revert to the default
     * {@link SecureRandom}.</p>
     *
     * <p>This is intended to be wrapped in a try/finally by the caller to ensure
     * the seed is cleared after rendering a rule response:</p>
     * <pre>
     * FakerProvider.setSeed(rule.getFakerSeed());
     * try {
     *     String body = TemplateEngine.render(template, ctx);
     * } finally {
     *     FakerProvider.setSeed(null);
     * }
     * </pre>
     *
     * @param seed seed value, or null to clear
     */
    public static void setSeed(Long seed) {
        if (seed == null) {
            SEEDED_RND.remove();
        } else {
            SEEDED_RND.set(new Random(seed));
        }
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

        // randomElement [a,b,c] — note: the rest of the string after "randomElement" is the arg
        if (functionName.equals("randomElement") || functionName.startsWith("randomElement ")) {
            String arg = functionName.startsWith("randomElement ")
                    ? functionName.substring("randomElement ".length())
                    : "";
            return resolveRandomElement(arg);
        }

        // regexify 'pattern' — note: the rest of the string after "regexify" is the arg
        if (functionName.equals("regexify") || functionName.startsWith("regexify ")) {
            String arg = functionName.startsWith("regexify ")
                    ? functionName.substring("regexify ".length())
                    : "";
            return resolveRegexify(arg);
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
                return String.valueOf(rnd().nextInt(10000));
            case "float":
            case "decimal":
                return String.format("%.2f", rnd().nextDouble() * 10000);
            case "boolean":
                return String.valueOf(rnd().nextBoolean());

            // String
            case "hex":
                return randomHex(8);
            case "alphaNumeric":
                return randomAlphaNumeric(8);
            case "string":
                return randomAlphaNumeric(16);

            // Misc
            case "locale":
                return LOCALES[rnd().nextInt(LOCALES.length)];
            case "userAgent":
                return USER_AGENTS[rnd().nextInt(USER_AGENTS.length)];
            case "statusCode":
                return HTTP_STATUS_CODES[rnd().nextInt(HTTP_STATUS_CODES.length)];
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
        return prefixes[rnd().nextInt(prefixes.length)] + randomDigits(8);
    }

    private static String randomEmail() {
        String user = randomAlphaNumeric(4 + rnd().nextInt(8)).toLowerCase();
        String domain = EMAIL_DOMAINS[rnd().nextInt(EMAIL_DOMAINS.length)];
        return user + "@" + domain;
    }

    private static String randomFullName() {
        return randomLastName() + randomFirstName();
    }

    private static String randomFirstName() {
        return FIRST_NAMES[rnd().nextInt(FIRST_NAMES.length)];
    }

    private static String randomLastName() {
        return LAST_NAMES[rnd().nextInt(LAST_NAMES.length)];
    }

    private static String randomProvince() {
        return PROVINCES[rnd().nextInt(PROVINCES.length)];
    }

    private static String randomCity() {
        int idx = rnd().nextInt(CITIES.length);
        String[] provinceCities = CITIES[idx];
        return provinceCities[rnd().nextInt(provinceCities.length)];
    }

    private static String randomStreetAddress() {
        String street = STREETS[rnd().nextInt(STREETS.length)];
        String number = String.valueOf(1 + rnd().nextInt(999));
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
        int year = 1960 + rnd().nextInt(50);
        int month = 1 + rnd().nextInt(12);
        int day = 1 + rnd().nextInt(28);
        String birth = String.format("%04d%02d%02d", year, month, day);
        String seq = String.format("%03d", 1 + rnd().nextInt(999));
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
        return COMPANIES_PREFIX[rnd().nextInt(COMPANIES_PREFIX.length)]
                + COMPANIES_PREFIX[rnd().nextInt(COMPANIES_PREFIX.length)]
                + COMPANIES_SUFFIX[rnd().nextInt(COMPANIES_SUFFIX.length)];
    }

    private static String randomUrl() {
        String[] protocols = {"http", "https"};
        String[] domains = {"example", "mock", "test", "demo", "api", "service"};
        String[] tlds = {".com", ".cn", ".io", ".net", ".org"};
        return protocols[rnd().nextInt(protocols.length)] + "://"
                + domains[rnd().nextInt(domains.length)]
                + randomDigits(2)
                + tlds[rnd().nextInt(tlds.length)];
    }

    private static String randomIpv4() {
        return (10 + rnd().nextInt(240)) + "."
                + rnd().nextInt(256) + "."
                + rnd().nextInt(256) + "."
                + (1 + rnd().nextInt(254));
    }

    private static String randomIpv6() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0) sb.append(":");
            sb.append(String.format("%04x", rnd().nextInt(65536)));
        }
        return sb.toString();
    }

    private static String randomMacAddress() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(":");
            sb.append(String.format("%02X", rnd().nextInt(256)));
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
            sb.append(HEX_CHARS[rnd().nextInt(16)]);
        }
        return sb.toString();
    }

    private static String randomHexColor() {
        return "#" + randomHex(6).toUpperCase();
    }

    private static String randomAlphaNumeric(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUMERIC[rnd().nextInt(ALPHANUMERIC.length)]);
        }
        return sb.toString();
    }

    private static String randomDigits(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(rnd().nextInt(10));
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
                return String.valueOf(min + rnd().nextInt(max - min + 1));
            } catch (NumberFormatException e) {
                // fall through to default
            }
        }
        return String.valueOf(rnd().nextInt(10000));
    }

    /**
     * Resolve {@code randomElement [a,b,c]} — pick a random element from a comma-separated list.
     * Surrounding brackets are optional. Empty input returns an empty string.
     * Elements are not trimmed of internal whitespace, but leading/trailing whitespace
     * around the whole list and around the brackets is stripped.
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code randomElement [a,b,c]} → one of "a", "b", "c"</li>
     *   <li>{@code randomElement apple,banana,cherry} → one of the three</li>
     *   <li>{@code randomElement [1,2,3]} → one of "1", "2", "3"</li>
     * </ul>
     */
    private static String resolveRandomElement(String arg) {
        if (arg == null || arg.isEmpty()) {
            return "";
        }
        Matcher m = RANDOM_ELEMENT_PATTERN.matcher(arg);
        String inner = m.matches() ? m.group(1) : arg;
        if (inner == null || inner.isEmpty()) {
            return "";
        }
        String[] elements = inner.split(",");
        if (elements.length == 0) {
            return "";
        }
        // Trim each element so "[a, b, c]" works as users expect.
        for (int i = 0; i < elements.length; i++) {
            elements[i] = elements[i].trim();
        }
        return elements[rnd().nextInt(elements.length)];
    }

    /**
     * Resolve {@code regexify 'pattern'} — generate a string matching the given regex.
     * The pattern may be wrapped in single or double quotes (which are stripped).
     * Uses a bounded backtracking generator that supports the most common regex
     * constructs: literal chars, character classes {@code [a-z]}, quantifiers
     * {@code ? * + {n} {n,m}}, alternation {@code a|b}, and groups {@code (...)}.
     *
     * <p>If the pattern cannot be parsed, the original pattern string is returned
     * (so the user sees the unresolved template in the response, making the
     * misconfiguration visible).</p>
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code regexify '[A-Z]{3}[0-9]{4}'} → e.g. "ABC1234"</li>
     *   <li>{@code regexify 'foo|bar|baz'} → one of "foo", "bar", "baz"</li>
     * </ul>
     */
    private static String resolveRegexify(String arg) {
        if (arg == null || arg.isEmpty()) {
            return "";
        }
        Matcher m = REGEXIFY_QUOTE_PATTERN.matcher(arg);
        String pattern = m.matches() ? m.group(1) : arg;
        if (pattern == null || pattern.isEmpty()) {
            return "";
        }
        try {
            return new RegexGenerator(pattern, rnd()).generate();
        } catch (PatternSyntaxException e) {
            // Return the raw pattern so the misconfiguration is visible in the response.
            return pattern;
        } catch (Exception e) {
            return pattern;
        }
    }

    // --- Regex generator for regexify ---

    /**
     * Minimal regex string generator supporting literals, character classes
     * {@code [a-z]}, quantifiers {@code ? * + {n} {n,m}}, alternation {@code |},
     * and non-capturing groups {@code (...)}. Anchors {@code ^ $} are ignored.
     *
     * <p>This is intentionally simple — it does not support backreferences,
     * lookaround, or Unicode classes. It exists to support the common case of
     * generating test data from simple patterns like {@code [A-Z]{3}[0-9]{4}}.</p>
     */
    static final class RegexGenerator {
        private final String pattern;
        private final Random random;
        private int pos;

        RegexGenerator(String pattern, Random random) {
            this.pattern = pattern;
            this.random = random;
            this.pos = 0;
        }

        String generate() {
            StringBuilder sb = new StringBuilder();
            // Top-level: alternation over '|'-separated sequences.
            sb.append(parseAlternation());
            return sb.toString();
        }

        private String parseAlternation() {
            StringBuilder sb = new StringBuilder();
            sb.append(parseSequence());
            // Collect alternatives so we can pick one at random.
            java.util.List<String> alternatives = new java.util.ArrayList<String>();
            alternatives.add(sb.toString());
            while (pos < pattern.length() && pattern.charAt(pos) == '|') {
                pos++; // consume '|'
                StringBuilder alt = new StringBuilder();
                alt.append(parseSequence());
                alternatives.add(alt.toString());
            }
            if (alternatives.size() == 1) {
                return alternatives.get(0);
            }
            return alternatives.get(random.nextInt(alternatives.size()));
        }

        private String parseSequence() {
            StringBuilder sb = new StringBuilder();
            while (pos < pattern.length()) {
                char c = pattern.charAt(pos);
                if (c == '|' || c == ')') {
                    break;
                }
                String unit = parseUnit();
                // Apply quantifier if present
                unit = applyQuantifier(unit);
                sb.append(unit);
            }
            return sb.toString();
        }

        private String parseUnit() {
            char c = pattern.charAt(pos);
            if (c == '(') {
                pos++; // consume '('
                String result = parseAlternation();
                if (pos < pattern.length() && pattern.charAt(pos) == ')') {
                    pos++; // consume ')'
                }
                return result;
            }
            if (c == '[') {
                return parseCharClass();
            }
            if (c == '\\' && pos + 1 < pattern.length()) {
                pos += 2;
                return resolveEscape(pattern.charAt(pos - 1));
            }
            if (c == '^' || c == '$') {
                // Anchors are ignored for generation purposes.
                pos++;
                return "";
            }
            if (c == '.' && pos + 1 < pattern.length() && pattern.charAt(pos + 1) == '*') {
                // Avoid runaway generation for ".*" — emit a short random string.
                pos += 2; // consume both '.' and '*'
                return randomAlpha(3);
            }
            if (c == '.') {
                pos++;
                return randomAlpha(1);
            }
            pos++;
            return String.valueOf(c);
        }

        private String parseCharClass() {
            // Consume '['
            pos++;
            boolean negate = false;
            if (pos < pattern.length() && pattern.charAt(pos) == '^') {
                negate = true;
                pos++;
            }
            java.util.List<char[]> ranges = new java.util.ArrayList<char[]>();
            java.util.List<Character> singles = new java.util.ArrayList<Character>();
            while (pos < pattern.length() && pattern.charAt(pos) != ']') {
                char ch = pattern.charAt(pos);
                if (ch == '\\' && pos + 1 < pattern.length()) {
                    pos++;
                    char escaped = pattern.charAt(pos);
                    pos++;
                    // Treat escape as a single literal char in the class.
                    singles.add(escaped);
                    continue;
                }
                // Range: a-z
                if (pos + 2 < pattern.length() && pattern.charAt(pos + 1) == '-'
                        && pattern.charAt(pos + 2) != ']') {
                    char lo = ch;
                    char hi = pattern.charAt(pos + 2);
                    pos += 3;
                    ranges.add(new char[]{lo, hi});
                } else {
                    singles.add(ch);
                    pos++;
                }
            }
            // Consume ']'
            if (pos < pattern.length() && pattern.charAt(pos) == ']') {
                pos++;
            }
            // Build the candidate set.
            java.util.List<Character> candidates = new java.util.ArrayList<Character>();
            for (char[] r : ranges) {
                for (char ch = r[0]; ch <= r[1]; ch++) {
                    candidates.add(ch);
                }
            }
            for (Character ch : singles) {
                candidates.add(ch);
            }
            if (candidates.isEmpty()) {
                return "";
            }
            if (negate) {
                // Pick a printable ASCII char not in the candidate set.
                java.util.Set<Character> excluded = new java.util.HashSet<Character>(candidates);
                java.util.List<Character> complement = new java.util.ArrayList<Character>();
                for (char ch = 32; ch < 127; ch++) {
                    if (!excluded.contains(ch)) {
                        complement.add(ch);
                    }
                }
                if (complement.isEmpty()) {
                    return "";
                }
                return String.valueOf(complement.get(random.nextInt(complement.size())));
            }
            return String.valueOf(candidates.get(random.nextInt(candidates.size())));
        }

        private String applyQuantifier(String unit) {
            if (pos >= pattern.length()) {
                return unit;
            }
            char c = pattern.charAt(pos);
            if (c == '?') {
                pos++;
                return random.nextBoolean() ? unit : "";
            }
            if (c == '*') {
                pos++;
                int count = random.nextInt(4); // 0-3 repetitions
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    sb.append(unit);
                }
                return sb.toString();
            }
            if (c == '+') {
                pos++;
                int count = 1 + random.nextInt(3); // 1-3 repetitions
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    sb.append(unit);
                }
                return sb.toString();
            }
            if (c == '{') {
                int end = pattern.indexOf('}', pos);
                if (end < 0) {
                    return unit;
                }
                String spec = pattern.substring(pos + 1, end);
                pos = end + 1;
                int min, max;
                int comma = spec.indexOf(',');
                if (comma < 0) {
                    // {n} — exact
                    try {
                        min = max = Integer.parseInt(spec.trim());
                    } catch (NumberFormatException e) {
                        return unit;
                    }
                } else {
                    String minStr = spec.substring(0, comma).trim();
                    String maxStr = spec.substring(comma + 1).trim();
                    try {
                        min = minStr.isEmpty() ? 0 : Integer.parseInt(minStr);
                        max = maxStr.isEmpty() ? min + 3 : Integer.parseInt(maxStr);
                    } catch (NumberFormatException e) {
                        return unit;
                    }
                }
                if (max < min) {
                    max = min;
                }
                // Cap to avoid runaway generation.
                if (max > 32) {
                    max = 32;
                }
                int count = min + (max > min ? random.nextInt(max - min + 1) : 0);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    sb.append(unit);
                }
                return sb.toString();
            }
            return unit;
        }

        private String resolveEscape(char escaped) {
            switch (escaped) {
                case 'd':
                    return String.valueOf(random.nextInt(10));
                case 'D':
                    return String.valueOf((char) ('A' + random.nextInt(26)));
                case 'w':
                    String w = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_";
                    return String.valueOf(w.charAt(random.nextInt(w.length())));
                case 's':
                    return " ";
                case 'n':
                    return "\n";
                case 't':
                    return "\t";
                case 'r':
                    return "\r";
                default:
                    return String.valueOf(escaped);
            }
        }

        private String randomAlpha(int len) {
            String alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                sb.append(alpha.charAt(random.nextInt(alpha.length())));
            }
            return sb.toString();
        }
    }
}
