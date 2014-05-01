package com.icsynergy.awsproject;

import com.icsynergy.helpers.ITResHelper;
import com.icsynergy.helpers.JDBCHelper;

import com.icsynergy.helpers.SysConfigHelper;

import java.sql.Connection;

import java.sql.ResultSet;
import java.sql.Statement;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import oracle.iam.identity.orgmgmt.api.OrganizationManager;
import oracle.iam.identity.orgmgmt.api.OrganizationManagerConstants;
import oracle.iam.identity.orgmgmt.vo.Organization;
import oracle.iam.platform.Platform;
import oracle.iam.platform.entitymgr.vo.SearchCriteria;
import oracle.iam.scheduler.vo.TaskSupport;

public class OrgReconTask extends TaskSupport {
    private static final String TAG = "OrgReconTask";
    private final static Logger m_logger = Logger.getLogger("com.icsynergy");
    
    private static final String SQL = "select MANAGEMENT_GROUP, MANAGEMENT_GROUP_PIN from WSP_VENUE_MANAGEMENT_GROUP group by MANAGEMENT_GROUP, MANAGEMENT_GROUP_PIN";
    //private static final String SQL = "select MANAGEMENT_GROUP, MANAGEMENT_GROUP_PIN, MANGEMENT_GROUP_ID from WSP_VENUE_MANAGEMENT_GROUP group by MANAGEMENT_GROUP, MANAGEMENT_GROUP_PIN, MANGEMENT_GROUP_ID";
    
    //TODO add parameter with IT ResName to get DB connection details        
    private static final String SYSVARCODE = "AWS.DBITResName";
    
    private static final String UDFPIN = "pin";
    private static final String UDFGRPID = "grp_id";

    public void execute(HashMap hashMap) throws Exception {    
        m_logger.entering(TAG, "execute", hashMap.toString());
        
        JDBCHelper helper = null;
        
        String strITResName = SysConfigHelper.getPropValue(SYSVARCODE);
        if( strITResName == null)
            throw new Exception( "Can't get a value for a system property: " + SYSVARCODE);

        ITResHelper itresHelper = new ITResHelper( strITResName );  
        Map<String, String> map = itresHelper.getAllParams();
        
        if( map.containsKey(ITResHelper.Constants.GTCDBUserNameParamName) )
            map.put("User", map.get(ITResHelper.Constants.GTCDBUserNameParamName));
        
        if( map.containsKey(ITResHelper.Constants.GTCDBPasswordParamName) )
            map.put("Pwd", map.get(ITResHelper.Constants.GTCDBPasswordParamName));

        if( map.containsKey(ITResHelper.Constants.GTCDBURLParamName) )
            map.put("URL", map.get(ITResHelper.Constants.GTCDBURLParamName));

        if( map.containsKey(ITResHelper.Constants.GTCDBDriverParamName) )
            helper = new JDBCHelper(map.get(ITResHelper.Constants.GTCDBDriverParamName), map);
        
        assert helper != null;
        m_logger.finest("Helper class created");
        
        Connection conn = helper.getConnection();
        if( conn == null ){
            m_logger.severe("Can't get a connection to DB");
            return;
        }
        m_logger.finest("Connection created");
        
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(SQL);
        
        OrganizationManager orgMgr = Platform.getService(OrganizationManager.class);
        
        if( orgMgr == null )
            throw new Exception("Can't get Organization interface");
        
        Organization org = null;
        
        // cycle through all DB records
        while( rs.next() ){
            
            // look for orgs with the specified Management Group ID
            SearchCriteria crit = new SearchCriteria(UDFGRPID, rs.getInt("MANAGEMENT_GROUP_ID"), SearchCriteria.Operator.EQUAL);
            
            // return attributes set
            Set<String> setAttrs = new HashSet<String>();
            setAttrs.add(OrganizationManagerConstants.AttributeName.ORG_NAME.getId());
            setAttrs.add(OrganizationManagerConstants.AttributeName.ID_FIELD.getId());
            setAttrs.add(UDFPIN);
            setAttrs.add(UDFGRPID);
            
            // get a list of orgs with criteria set above
            List<Organization> listOrgs = orgMgr.search(crit, setAttrs, null);
            switch( listOrgs.size() ){
            
            // no org found
            case 0 :
                org = new Organization();
                org.setAttribute(OrganizationManagerConstants.AttributeName.ORG_NAME.getId(), rs.getString("MANAGEMENT_GROUP"));
                org.setAttribute(OrganizationManagerConstants.AttributeName.ORG_TYPE.getId(), "Company");
                org.setAttribute(UDFPIN, rs.getString("MANAGEMENT_GROUP_PIN"));
                org.setAttribute(UDFGRPID, rs.getInt("MANAGEMENT_GROUP_ID"));
                
                String strStatus = orgMgr.create(org);
                m_logger.fine("Organization created: " + rs.getString("MANAGEMENT_GROUP") + ". Status: " + strStatus);
                break;
            
            // there is only one
            case 1:
                boolean bChangeName = false;
                boolean bChangePIN = false;
                
                // if name is different from the query -> set the flag
                if( !listOrgs.get(0).getAttribute(OrganizationManagerConstants.AttributeName.ORG_NAME.getId()).toString().equalsIgnoreCase(rs.getString("MANAGEMENT_GROUP")) )
                    bChangeName = true;
                
                // if PIN has been changed -> set the flag
                if( !listOrgs.get(0).getAttribute(UDFPIN).toString().equalsIgnoreCase(rs.getString("MANAGEMENT_GROUP_PIN")) )
                    bChangePIN = true;
                
                if( bChangeName || bChangePIN ) {
                    org = new Organization(listOrgs.get(0).getEntityId());
                    
                    if( bChangeName )
                        org.setAttribute(OrganizationManagerConstants.AttributeName.ORG_NAME.getId(), rs.getString("MANAGEMENT_GROUP"));
                    
                    if( bChangePIN )
                        org.setAttribute(UDFPIN, rs.getString("MANAGEMENT_GROUP_PIN"));
                    
                    strStatus = orgMgr.modify(org);
                    m_logger.fine("Organization in OIM has been changed to " + rs.getString("MANAGEMENT_GROUP") + " PIN: " + rs.getString("MANAGEMENT_GROUP_PIN") +
                                  " for the Org with ID: " + rs.getString("MANAGEMENT_GROUP_ID") + ". Status of the operation: " + strStatus );
                } else            
                    m_logger.finest("Organization " + rs.getString("MANAGEMENT_GROUP") + " exists in OIM and has all the same attributes. Skipping");
                break;
            
            // there is more that one
            default:
                m_logger.severe("More than one organization found for the ID: " + rs.getString("MANAGEMENT_GROUP_ID"));
            }
        }
        stmt.close();
        conn.close();
        
        m_logger.exiting(TAG, "execute");
    }

    public HashMap getAttributes() {
        return new HashMap();
    }

    public void setAttributes() {
    }
}