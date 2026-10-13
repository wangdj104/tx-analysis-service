package org.familyhealthcare.service;

/** Exact synthetic-service identity, shared by the native acceptance helpers. */
final class NativeMysqlTestVersion {
    private NativeMysqlTestVersion() { }
    static String selected() {
        String value=System.getenv().getOrDefault("CARE_PLAN_MYSQL_VERSION", "8.0");
        if (!"8.0".equals(value) && !"5.7.18".equals(value))
            throw new IllegalStateException("Unsupported explicit synthetic MySQL version");
        return value;
    }
    static boolean is57() { return "5.7.18".equals(selected()); }
    static boolean matches(String expected, String actual) {
        if (actual==null) return false;
        if ("5.7.18".equals(expected)) return actual.matches("5\\.7\\.18(?:-[A-Za-z0-9.-]+)?");
        if ("8.0".equals(expected)) return actual.matches("8\\.0\\.[0-9]+(?:-[A-Za-z0-9.-]+)?");
        return false;
    }
}
