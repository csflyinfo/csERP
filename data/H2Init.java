import java.sql.*;

public class H2Init {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:h2:file:e:/我的工作项目/erp-wms-tms/backend/data/erp-v1;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                // 1. 设置员工 E00001 为司机，加手机号
                int n = st.executeUpdate(
                    "UPDATE base_employee SET is_deliveryman = TRUE, mobile = '13800000001' " +
                    "WHERE employee_code = 'E00001'");
                System.out.println("设置司机 E00001: " + n + " 行");

                // 2. 给线路设置 driver
                n = st.executeUpdate(
                    "UPDATE base_route_line SET driver = '业务王', vehicle_plate = '湘A12345', " +
                    "vehicle_type = '4.2米厢式', load_capacity = 500 " +
                    "WHERE route_line_code = '01'");
                System.out.println("更新线路 01 driver: " + n + " 行");

                conn.commit();
                System.out.println("事务已提交！");

            } catch (Exception e) {
                conn.rollback();
                throw e;
            }

            // 验证（自动提交模式）
            try (Statement st = conn.createStatement()) {
                ResultSet rs = st.executeQuery(
                    "SELECT employee_id, employee_code, employee_name, mobile, is_deliveryman " +
                    "FROM base_employee WHERE is_deliveryman = TRUE");
                System.out.println("\n=== 司机列表 ===");
                while (rs.next()) {
                    System.out.println("  " + rs.getString(1) + " | " + rs.getString(2) + " | " +
                        rs.getString(3) + " | " + rs.getString(4) + " | deliveryman=" + rs.getBoolean(5));
                }

                rs = st.executeQuery(
                    "SELECT route_line_code, route_line_name, driver, vehicle_plate FROM base_route_line");
                System.out.println("\n=== 线路列表 ===");
                while (rs.next()) {
                    System.out.println("  " + rs.getString(1) + " | " + rs.getString(2) + " | " +
                        "driver=" + rs.getString(3) + " | plate=" + rs.getString(4));
                }

                // 查看发货单
                rs = st.executeQuery(
                    "SELECT receipt_no, customer_code, status, sign_status FROM sales_receipt");
                System.out.println("\n=== 发货单 ===");
                while (rs.next()) {
                    System.out.println("  " + rs.getString(1) + " | customer=" + rs.getString(2) +
                        " | status=" + rs.getString(3) + " | sign_status=" + rs.getString(4));
                }
            }
        }
    }
}
