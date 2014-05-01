package com.icsynergy.helpers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import java.util.Map;
import java.util.logging.Logger;

public class JDBCHelper {
    private final static Logger m_logger = Logger.getLogger("com.icsynergy");
    private final static String TAG = "JDBCHelper";
    
    private String m_strUser = null;
    private String m_strPwd = null;
    private String m_strURL = null;
    private String m_strDriver = null;
    
    private Connection m_conn = null;
    
    public JDBCHelper( String strClassName, Map<String, String> mapParams ) {
        m_logger.entering(TAG, "<init>");
        
        if( strClassName != null)
            m_strDriver = strClassName;
        else
            m_logger.severe("Driver class is null");
        
        if( mapParams.containsKey("URL") && mapParams.get("URL") != null)
            m_strURL = mapParams.get("URL");
        else
            m_logger.severe("URL is null");
        
        if( mapParams.containsKey("User") && mapParams.get("User") != null)
            m_strUser = mapParams.get("User");
        else
            m_logger.severe("User is null");
        
        if( mapParams.containsKey("Pwd") && mapParams.get("Pwd") != null)
            m_strPwd = mapParams.get("Pwd");
        else
            m_logger.severe("Pwd is null");
        
        m_logger.exiting(TAG, "<init>");
    }
    
    public Connection getConnection() {
        if( m_conn == null ){

            try {
                m_conn = DriverManager.getConnection(m_strURL, m_strUser, m_strPwd);
            } catch (SQLException e) {
                m_logger.severe("Exception: "+e.toString());
            }
        }
        
        return m_conn;
    }
/*    
    public static void main( String[] arg) throws SQLException {
        Map map = new HashMap<String,String>();
        map.put("User", "tester");
        map.put("Pwd", "Password1");
        map.put("URL", "jdbc:oracle:thin:@192.168.10.29:1521:orcl");
        JDBCHelper helper = new JDBCHelper("oracle.jdbc.driver.OracleDriver", map);
        
        Connection conn = helper.getConnection();
        
        Statement stmt = conn.createStatement();
        
        stmt.executeQuery("select MANAGEMENT_GROUP, MANAGEMENT_GROUP_PIN, VENUE_CODE from export_table");
        
        stmt.close();
    }
*/
}
