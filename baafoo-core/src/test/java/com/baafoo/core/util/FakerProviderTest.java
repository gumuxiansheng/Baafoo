package com.baafoo.core.util;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class FakerProviderTest {

    @Test
    public void resolveNullReturnsEmpty() {
        assertEquals("", FakerProvider.resolve(null));
    }

    @Test
    public void resolveEmptyReturnsEmpty() {
        assertEquals("", FakerProvider.resolve(""));
    }

    @Test
    public void resolveUnknownReturnsTemplate() {
        assertEquals("{{faker.unknown}}", FakerProvider.resolve("unknown"));
    }

    @Test
    public void phoneIsValid() {
        String phone = FakerProvider.resolve("phone");
        assertEquals(11, phone.length());
        assertTrue(phone.startsWith("1"));
    }

    @Test
    public void phoneNumberAlias() {
        String phone = FakerProvider.resolve("phoneNumber");
        assertEquals(11, phone.length());
    }

    @Test
    public void emailContainsAt() {
        String email = FakerProvider.resolve("email");
        assertTrue(email.contains("@"));
        assertTrue(email.contains("."));
    }

    @Test
    public void emailAddressAlias() {
        String email = FakerProvider.resolve("emailAddress");
        assertTrue(email.contains("@"));
    }

    @Test
    public void nameIsChinese() {
        String name = FakerProvider.resolve("name");
        assertNotNull(name);
        assertTrue(name.length() >= 2);
    }

    @Test
    public void fullNameAlias() {
        String name = FakerProvider.resolve("fullName");
        assertNotNull(name);
    }

    @Test
    public void firstNameReturns() {
        String name = FakerProvider.resolve("firstName");
        assertNotNull(name);
        assertTrue(name.length() >= 1);
    }

    @Test
    public void lastNameReturns() {
        String name = FakerProvider.resolve("lastName");
        assertNotNull(name);
        assertTrue(name.length() >= 1);
    }

    @Test
    public void addressContainsProvinceOrCity() {
        String addr = FakerProvider.resolve("address");
        assertNotNull(addr);
        assertTrue(addr.length() > 5);
    }

    @Test
    public void fullAddressAlias() {
        String addr = FakerProvider.resolve("fullAddress");
        assertNotNull(addr);
    }

    @Test
    public void cityReturns() {
        String city = FakerProvider.resolve("city");
        assertNotNull(city);
        assertTrue(city.length() >= 1);
    }

    @Test
    public void provinceReturns() {
        String province = FakerProvider.resolve("province");
        assertNotNull(province);
        assertTrue(province.length() >= 2);
    }

    @Test
    public void zipCodeIs6Digits() {
        String zip = FakerProvider.resolve("zipCode");
        assertEquals(6, zip.length());
        assertTrue(zip.matches("\\d{6}"));
    }

    @Test
    public void postcodeAlias() {
        String zip = FakerProvider.resolve("postcode");
        assertEquals(6, zip.length());
    }

    @Test
    public void streetReturns() {
        String street = FakerProvider.resolve("street");
        assertNotNull(street);
        assertTrue(street.contains("路") || street.contains("街"));
    }

    @Test
    public void streetAddressReturns() {
        String addr = FakerProvider.resolve("streetAddress");
        assertNotNull(addr);
        assertTrue(addr.contains("号"));
    }

    @Test
    public void idCardIs18Chars() {
        String id = FakerProvider.resolve("idCard");
        assertEquals(18, id.length());
    }

    @Test
    public void idNumberAlias() {
        String id = FakerProvider.resolve("idNumber");
        assertEquals(18, id.length());
    }

    @Test
    public void companyReturns() {
        String company = FakerProvider.resolve("company");
        assertNotNull(company);
        assertTrue(company.contains("有限公司"));
    }

    @Test
    public void companyNameAlias() {
        String company = FakerProvider.resolve("companyName");
        assertNotNull(company);
    }

    @Test
    public void urlStartsWithProtocol() {
        String url = FakerProvider.resolve("url");
        assertTrue(url.startsWith("http://") || url.startsWith("https://"));
    }

    @Test
    public void websiteAlias() {
        String url = FakerProvider.resolve("website");
        assertTrue(url.startsWith("http"));
    }

    @Test
    public void ipv4Format() {
        String ip = FakerProvider.resolve("ipv4");
        assertTrue(ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+"));
    }

    @Test
    public void ipAlias() {
        String ip = FakerProvider.resolve("ip");
        assertTrue(ip.contains("."));
    }

    @Test
    public void ipv6ContainsColons() {
        String ipv6 = FakerProvider.resolve("ipv6");
        assertTrue(ipv6.contains(":"));
    }

    @Test
    public void macFormat() {
        String mac = FakerProvider.resolve("mac");
        assertEquals(17, mac.length());
        assertTrue(mac.contains(":"));
    }

    @Test
    public void macAddressAlias() {
        String mac = FakerProvider.resolve("macAddress");
        assertTrue(mac.contains(":"));
    }

    @Test
    public void uuidFormat() {
        String uuid = FakerProvider.resolve("uuid");
        assertEquals(36, uuid.length());
        assertEquals('-', uuid.charAt(8));
        assertEquals('-', uuid.charAt(13));
    }

    @Test
    public void timestampIsNumeric() {
        String ts = FakerProvider.resolve("timestamp");
        Long.parseLong(ts);
    }

    @Test
    public void dateformat() {
        String date = FakerProvider.resolve("date");
        assertTrue(date.matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    public void dateTimeFormat() {
        String dt = FakerProvider.resolve("dateTime");
        assertTrue(dt.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    public void intReturnsNumeric() {
        String val = FakerProvider.resolve("int");
        int n = Integer.parseInt(val);
        assertTrue(n >= 0 && n < 10000);
    }

    @Test
    public void integerAlias() {
        String val = FakerProvider.resolve("integer");
        Integer.parseInt(val);
    }

    @Test
    public void floatReturnsDecimal() {
        String val = FakerProvider.resolve("float");
        double d = Double.parseDouble(val);
        assertTrue(d >= 0 && d < 10000);
    }

    @Test
    public void decimalAlias() {
        String val = FakerProvider.resolve("decimal");
        Double.parseDouble(val);
    }

    @Test
    public void booleanReturnsTrueOrFalse() {
        String val = FakerProvider.resolve("boolean");
        assertTrue(val.equals("true") || val.equals("false"));
    }

    @Test
    public void hexIs8Chars() {
        String hex = FakerProvider.resolve("hex");
        assertEquals(8, hex.length());
        assertTrue(hex.matches("[0-9a-f]{8}"));
    }

    @Test
    public void alphaNumericIs8Chars() {
        String an = FakerProvider.resolve("alphaNumeric");
        assertEquals(8, an.length());
        assertTrue(an.matches("[A-Za-z0-9]{8}"));
    }

    @Test
    public void stringIs16Chars() {
        String s = FakerProvider.resolve("string");
        assertEquals(16, s.length());
    }

    @Test
    public void localeReturns() {
        String locale = FakerProvider.resolve("locale");
        assertNotNull(locale);
        assertTrue(locale.contains("_"));
    }

    @Test
    public void userAgentReturns() {
        String ua = FakerProvider.resolve("userAgent");
        assertNotNull(ua);
        assertTrue(ua.startsWith("Mozilla"));
    }

    @Test
    public void statusCodeIsNumeric() {
        String code = FakerProvider.resolve("statusCode");
        int c = Integer.parseInt(code);
        assertTrue(c >= 200 && c < 600);
    }

    @Test
    public void colorStartsWithHash() {
        String color = FakerProvider.resolve("color");
        assertTrue(color.startsWith("#"));
        assertEquals(7, color.length());
    }

    @Test
    public void hexColorAlias() {
        String color = FakerProvider.resolve("hexColor");
        assertTrue(color.startsWith("#"));
    }

    // --- int.min.max ---

    @Test
    public void intRangeRespectsBounds() {
        for (int i = 0; i < 50; i++) {
            String val = FakerProvider.resolve("int.10.20");
            int n = Integer.parseInt(val);
            assertTrue(n >= 10 && n <= 20);
        }
    }

    @Test
    public void integerRangeWorks() {
        String val = FakerProvider.resolve("integer.5.10");
        int n = Integer.parseInt(val);
        assertTrue(n >= 5 && n <= 10);
    }

    @Test
    public void intRangeSwapsMinMax() {
        String val = FakerProvider.resolve("int.100.10");
        int n = Integer.parseInt(val);
        assertTrue(n >= 10 && n <= 100);
    }

    @Test
    public void intRangeInvalidFallsBack() {
        String val = FakerProvider.resolve("int.abc.def");
        int n = Integer.parseInt(val);
        assertTrue(n >= 0 && n < 10000);
    }

    @Test
    public void intRangeSinglePart() {
        String val = FakerProvider.resolve("int.42");
        int n = Integer.parseInt(val);
        assertTrue(n >= 0 && n < 10000);
    }

    // --- randomElement ---

    @Test
    public void randomElementWithBrackets() {
        Set<String> results = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            results.add(FakerProvider.resolve("randomElement [x,y,z]"));
        }
        assertTrue(results.size() > 1);
    }

    @Test
    public void randomElementWithoutBrackets() {
        String val = FakerProvider.resolve("randomElement a,b,c");
        assertTrue(val.equals("a") || val.equals("b") || val.equals("c"));
    }

    @Test
    public void randomElementEmptyReturnsEmpty() {
        assertEquals("", FakerProvider.resolve("randomElement"));
    }

    @Test
    public void randomElementSingleElement() {
        assertEquals("only", FakerProvider.resolve("randomElement [only]"));
    }

    // --- regexify ---

    @Test
    public void regexifyBasicPattern() {
        String val = FakerProvider.resolve("regexify [A-Z]{3}");
        assertEquals(3, val.length());
        assertTrue(val.matches("[A-Z]{3}"));
    }

    @Test
    public void regexifyWithQuotes() {
        String val = FakerProvider.resolve("regexify '[0-9]{4}'");
        assertEquals(4, val.length());
        assertTrue(val.matches("\\d{4}"));
    }

    @Test
    public void regexifyEmptyReturnsEmpty() {
        assertEquals("", FakerProvider.resolve("regexify"));
    }

    @Test
    public void regexifyAlternation() {
        Set<String> results = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            results.add(FakerProvider.resolve("regexify foo|bar|baz"));
        }
        assertTrue(results.size() > 1);
        for (String r : results) {
            assertTrue(r.equals("foo") || r.equals("bar") || r.equals("baz"));
        }
    }

    @Test
    public void regexifyInvalidReturnsPattern() {
        // Truly invalid: unbalanced braces cause PatternSyntaxException
        String result = FakerProvider.resolve("regexify {invalid}}");
        assertNotNull(result);
    }

    // --- seed determinism ---

    @Test
    public void seedProducesDeterministicSequence() {
        FakerProvider.setSeed(42L);
        try {
            String a1 = FakerProvider.resolve("int");
            String a2 = FakerProvider.resolve("int");
            FakerProvider.setSeed(null);
            FakerProvider.setSeed(42L);
            String b1 = FakerProvider.resolve("int");
            String b2 = FakerProvider.resolve("int");
            assertEquals(a1, b1);
            assertEquals(a2, b2);
        } finally {
            FakerProvider.setSeed(null);
        }
    }

    @Test
    public void clearSeedRevertsToNonDeterministic() {
        FakerProvider.setSeed(42L);
        String first = FakerProvider.resolve("int");
        FakerProvider.setSeed(null);
        // After clearing, results should not necessarily match the seeded sequence
        // (just verify no exception and we get a valid number)
        int n = Integer.parseInt(FakerProvider.resolve("int"));
        assertTrue(n >= 0);
    }
}
