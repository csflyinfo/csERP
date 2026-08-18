import java.sql.*;

public class H2Query {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:h2:file:e:/work/erp-wms-tms/backend/data/erp-v1;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            // 基础数据统计
            String[][] queries = {
                {"base_employee", "SELECT COUNT(*) FROM base_employee"},
                {"base_employee (deliveryman)", "SELECT COUNT(*) FROM base_employee WHERE is_deliveryman = TRUE"},
                {"base_customer", "SELECT COUNT(*) FROM base_customer"},
                {"base_goods", "SELECT COUNT(*) FROM base_goods"},
                {"base_warehouse", "SELECT COUNT(*) FROM base_warehouse"},
                {"base_route_line", "SELECT COUNT(*) FROM base_route_line"},
                {"sales_receipt", "SELECT COUNT(*) FROM sales_receipt"},
                {"sales_outbound", "SELECT COUNT(*) FROM sales_outbound"},
                {"tms_dispatch", "SELECT COUNT(*) FROM tms_dispatch"},
                {"tms_dispatch_detail", "SELECT COUNT(*) FROM tms_dispatch_detail"},
            };
            System.out.println("=== 基础数据统计 ===");
            for (String[] q : queries) {
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(q[1])) {
                    if (rs.next()) {
                        int c = rs.getInt(1);
                        String mark = c == 0 ? " [空]" : "";
                        System.out.println(String.format("  %-30s %d%s", q[0], c, mark));
                    }
                } catch (SQLException e) {
                    System.out.println("  " + q[0] + " -> ERROR: " + e.getMessage().split("\n")[0]);
                }
            }

            // 查看前3个员工（如果有）
            System.out.println("\n=== base_employee 前3条 ===");
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT employee_id, employee_code, employee_name, mobile, is_deliveryman, status " +
                     "FROM base_employee LIMIT 3")) {
                while (rs.next())
                    System.out.println("  " + rs.getString(1) + " | " + rs.getString(2) + " | " +
                        rs.getString(3) + " | " + rs.getString(4) + " | deliveryman=" + rs.getBoolean(5) +
                        " | " + rs.getString(6));
            } catch (SQLException e) {
                System.out.println("  ERROR: " + e.getMessage().split("\n")[0]);
            }

            // 查看前3个客户（如果有）
            System.out.println("\n=== base_customer 前3条 ===");
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT customer_id, customer_code, customer_name, mobile " +
                     "FROM base_customer LIMIT 3")) {
                while (rs.next())
                    System.out.println("  " + rs.getString(1) + " | " + rs.getString(2) + " | " +
                        rs.getString(3) + " | " + rs.getString(4));
            } catch (SQLException e) {
                System.out.println("  ERROR: " + e.getMessage().split("\n")[0]);
            }

            // 查看前3个线路
            System.out.println("\n=== base_route_line 前3条 ===");
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT route_line_id, route_line_code, route_line_name, driver, status " +
                     "FROM base_route_line LIMIT 3")) {
                while (rs.next())
                    System.out.println("  " + rs.getString(1) + " | " + rs.getString(2) + " | " +
                        rs.getString(3) + " | driver=" + rs.getString(4) + " | " + rs.getString(5));
            } catch (SQLException e) {
                System.out.println("  ERROR: " + e.getMessage().split("\n")[0]);
            }

            // 查看 sys_user_runtime 的密码
            System.out.println("\n=== sys_user_runtime 密码 ===");
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT username, password FROM sys_user_runtime WHERE username='admin'")) {
                if (rs.next()) {
                    String pwd = rs.getString(2);
                    System.out.println("  admin password hash prefix: " +
                        (pwd != null && pwd.length() > 10 ? pwd.substring(0, 10) + "..." : pwd));
                }
            }
        }
    }
}
