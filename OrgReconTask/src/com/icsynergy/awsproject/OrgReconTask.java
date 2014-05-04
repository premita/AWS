package com.icsynergy.awsproject;

import com.icsynergy.helpers.ITResHelper;
import com.icsynergy.helpers.JDBCHelper;
import com.icsynergy.helpers.SysConfigHelper;

import java.sql.Connection;

import java.sql.ResultSet;
import java.sql.Statement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import oracle.iam.identity.exception.RoleAlreadyExistsException;
import oracle.iam.identity.exception.RoleCreateException;
import oracle.iam.identity.exception.ValidationFailedException;
import oracle.iam.identity.orgmgmt.api.OrganizationManager;
import oracle.iam.identity.orgmgmt.api.OrganizationManagerConstants;
import oracle.iam.identity.orgmgmt.vo.Organization;
import oracle.iam.identity.rolemgmt.api.RoleManager;
import oracle.iam.identity.rolemgmt.api.RoleManagerConstants;
import oracle.iam.identity.rolemgmt.vo.Role;
import oracle.iam.identity.rolemgmt.vo.RoleManagerResult;
import oracle.iam.platform.Platform;
import oracle.iam.platform.authopss.api.PolicyConstants;
import oracle.iam.platform.authopss.vo.EntityPublication;
import oracle.iam.platform.entitymgr.vo.SearchCriteria;
import oracle.iam.platformservice.api.EntityPublicationService;
import oracle.iam.scheduler.vo.TaskSupport;

public class OrgReconTask extends TaskSupport {
  private static final String TAG = "OrgReconTask";
  private final static Logger m_logger = Logger.getLogger("com.icsynergy");

  //private static final String SQL = "select MANAGEMENT_GROUP, MANAGEMENT_GROUP_PIN from WSP_VENUE_MANAGEMENT_GROUP group by MANAGEMENT_GROUP, MANAGEMENT_GROUP_PIN";
  private static final String MG = "MANAGEMENT_GROUP";
  private static final String MGPIN = "MANAGEMENT_GROUP_PIN";
  private static final String MGID = "MANGEMENT_GROUP_ID";
  private static final String strSQLTemplate =
    "SELECT %s, %s, %s FROM %s WHERE %s NOT IN (%s) GROUP BY %s, %s, %s";

  //TODO add parameter with IT ResName to get DB connection details
  private static final String SYSVARCODE = "AWS.DBITResName";

  private static final String ROLENAMEPREFIX = "aws_delegated_admin_";
  private static final String UDFPIN = "pin";
  private static final String UDFGRPID = "grp_id";

  public void execute(HashMap hashMap) throws Exception {
    m_logger.entering(TAG, "execute", hashMap.toString());

    JDBCHelper helper = null;

    String strITResName = SysConfigHelper.getPropValue(SYSVARCODE);
    if (strITResName == null)
      throw new Exception("Can't get a value for a system property: " +
                          SYSVARCODE);

    // pull table name from a param
    String strTableName = hashMap.get("Table Name").toString();

    // check filter param
    String strFilterList =
      hashMap.containsKey("ID List To Filter Out") ? hashMap.get("ID List To Filter Out").toString() :
      null;

    if (strFilterList != null && strFilterList.length() > 0)
      strFilterList = strFilterList.replace('|', ',');
    else
      strFilterList = "0";

    // create final SQL statement
    String SQL =
      String.format(strSQLTemplate, MG, MGPIN, MGID, strTableName, MGID,
                    strFilterList, MG, MGPIN, MGID);
    m_logger.finest("SQL statement: " + SQL);

    ITResHelper itresHelper = new ITResHelper(strITResName);
    Map<String, String> map = itresHelper.getAllParams();

    if (map.containsKey(ITResHelper.Constants.GTCDBUserNameParamName))
      map.put("User", map.get(ITResHelper.Constants.GTCDBUserNameParamName));

    if (map.containsKey(ITResHelper.Constants.GTCDBPasswordParamName))
      map.put("Pwd", map.get(ITResHelper.Constants.GTCDBPasswordParamName));

    if (map.containsKey(ITResHelper.Constants.GTCDBURLParamName))
      map.put("URL", map.get(ITResHelper.Constants.GTCDBURLParamName));

    if (map.containsKey(ITResHelper.Constants.GTCDBDriverParamName))
      helper =
          new JDBCHelper(map.get(ITResHelper.Constants.GTCDBDriverParamName),
                         map);

    Connection conn = helper.getConnection();
    if (conn == null)
      throw new Exception("Can't get a connection to DB");

    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(SQL);

    OrganizationManager orgMgr =
      Platform.getService(OrganizationManager.class);
    if (orgMgr == null)
      throw new Exception("Can't get Organization interface");

    RoleManager roleMgr = Platform.getService(RoleManager.class);
    if (roleMgr == null)
      throw new Exception("Can't get role manager interface");

    EntityPublicationService srv =
      Platform.getService(EntityPublicationService.class);
    if (srv == null)
      throw new Exception("Can't get EntityPublication service");

    // list of entities to publish
    List<EntityPublication> lstEntities = new ArrayList<EntityPublication>();
    // ... to unpublish
    List<EntityPublication> lstUnpublish = new ArrayList<EntityPublication>();

    // get ID for organization "Top"
    Set setAttr = new HashSet();
    setAttr.add(OrganizationManagerConstants.AttributeName.ID_FIELD.getId());
    
    Organization org = orgMgr.getDetails("Top", setAttr, true);
    String strTopOrgId = org.getEntityId();

    // cycle through all DB records
    while (rs.next()) {

      // look for orgs with the specified Management Group ID
      SearchCriteria crit =
        new SearchCriteria(UDFGRPID, rs.getInt(MGID), SearchCriteria.Operator.EQUAL);

      // return attributes set
      Set<String> setAttrs = new HashSet<String>();
      setAttrs.add(OrganizationManagerConstants.AttributeName.ORG_NAME.getId());
      setAttrs.add(OrganizationManagerConstants.AttributeName.ID_FIELD.getId());
      setAttrs.add(UDFPIN);
      setAttrs.add(UDFGRPID);
      setAttrs.add(OrganizationManagerConstants.AttributeName.ORG_STATUS.getId());

      // get a list of orgs with criteria set above
      List<Organization> listOrgs = orgMgr.search(crit, setAttrs, null);
      switch (listOrgs.size()) {

        // no org found
      case 0:
        // fill in attributes
        org = new Organization();
        org.setAttribute(OrganizationManagerConstants.AttributeName.ORG_NAME.getId(),
                         rs.getString(MG));
        org.setAttribute(OrganizationManagerConstants.AttributeName.ORG_TYPE.getId(),
                         "Company");
        org.setAttribute(UDFPIN, rs.getString(MGPIN));
        org.setAttribute(UDFGRPID, rs.getInt(MGID));

        // create organization
        String strStatus = orgMgr.create(org);
        m_logger.fine("Organization created: " + rs.getString(MG) +
                      ". Status: " + strStatus);

        // map of a role attributes
        HashMap<String, Object> mapRoleAtt = new HashMap<String, Object>();
        mapRoleAtt.put(RoleManagerConstants.RoleAttributeName.NAME.getId(),
                       ROLENAMEPREFIX + rs.getString(MGID));

        // new role
        Role role = new Role(mapRoleAtt);

        // create
        RoleManagerResult rmResult = roleMgr.create(role);
        m_logger.finest("Role created, result: " + rmResult.getStatus());

        // entity to publish
        EntityPublication entPub = new EntityPublication();
        // role id
        entPub.setEntityId(rmResult.getEntityId());
        entPub.setEntityType(PolicyConstants.Resources.ROLE.getId());
        // org key
        entPub.setScopeId(strStatus);
        entPub.setHierarchicalScope(true);

        // add entity to publish
        lstEntities.add(entPub);
        
        List<EntityPublication> lstPublications = srv.listEntityPublications(PolicyConstants.Resources.ROLE, rmResult.getEntityId(), null);

        if( lstPublications.size() == 1 )
          lstUnpublish.add(lstPublications.get(0));
        break;

        // there is only one
      case 1:
        boolean bChangeName = false;
        boolean bChangePIN = false;
        
        //if organization is not Active -> skip it
        if( !listOrgs.get(0).getAttribute(OrganizationManagerConstants.AttributeName.ORG_STATUS.getId()).toString().equalsIgnoreCase("Active") )
          break;
        
        // if name is different from the query -> set the flag
        if (!listOrgs.get(0).getAttribute(OrganizationManagerConstants.AttributeName.ORG_NAME.getId()).toString().equalsIgnoreCase(rs.getString(MG)))
          bChangeName = true;

        // if PIN has been changed -> set the flag
        if (!listOrgs.get(0).getAttribute(UDFPIN).toString().equalsIgnoreCase(rs.getString(MGPIN)))
          bChangePIN = true;

        if (bChangeName || bChangePIN) {
          org = new Organization(listOrgs.get(0).getEntityId());

          if (bChangeName)
            org.setAttribute(OrganizationManagerConstants.AttributeName.ORG_NAME.getId(),
                             rs.getString(MG));

          if (bChangePIN)
            org.setAttribute(UDFPIN, rs.getString(MGPIN));

          strStatus = orgMgr.modify(org);
          m_logger.fine("Organization in OIM has been changed to " +
                        rs.getString(MG) + " PIN: " +
                        rs.getString(MGPIN) +
                        " for the Org with ID: " +
                        rs.getString(MGID) +
                        ". Status of the operation: " + strStatus);
          
        } else
          m_logger.finest("Organization " + rs.getString(MG) +
                          " exists in OIM and has all the same attributes. Checking for a role");
        
      // search for a role
      setAttr = new HashSet<String>();
      setAttr.add(RoleManagerConstants.RoleAttributeName.KEY.getId());
      crit = new SearchCriteria(RoleManagerConstants.RoleAttributeName.NAME.getId(), ROLENAMEPREFIX+rs.getString(MGID), SearchCriteria.Operator.EQUAL);
      
      List<Role> lstRole = roleMgr.search(crit, setAttr, null);
      
      boolean bPublish = false;
      String strRoleKey = null;
      
      // if role not found -> create it
      if( lstRole.size() == 0 ){
        m_logger.finest( "Role hasn't been found: " + ROLENAMEPREFIX + rs.getString(MGID) );
        RoleManagerResult res = createRole( ROLENAMEPREFIX + rs.getString(MGID) );
        
        // if role successfully created -> publish it to org
        bPublish = res.getStatus().equalsIgnoreCase("COMPLETED");
        strRoleKey = res.getEntityId();
        
        
        m_logger.finer("Role was created: " + ROLENAMEPREFIX + rs.getString(MGID));
      } else if( lstRole.size() == 1) {
        m_logger.finest("Role exists");
        bPublish = !isRolePublishedToOrg( lstRole.get(0).getEntityId(), listOrgs.get(0).getEntityId() );
        strRoleKey = lstRole.get(0).getEntityId();
      } else
        throw new Exception( "More than one role found with a name: " + ROLENAMEPREFIX + rs.getString(MGID));
      
      // publish if required
      if( bPublish ) {
        m_logger.finest("Role is required to be published to the org");
        entPub = new EntityPublication();
        entPub.setEntityId( strRoleKey );
        entPub.setEntityType( PolicyConstants.Resources.ROLE.getId() );
        entPub.setHierarchicalScope( true );
        entPub.setScopeId( listOrgs.get(0).getEntityId() );
        
        lstEntities.add( entPub );
        m_logger.finest("Role added for publication");
        
        // get EntPub for the role publication to Top
        List<EntityPublication> lstEntPubToTop = srv.listEntityPublicationInScope(PolicyConstants.Resources.ROLE, strRoleKey, strTopOrgId, false, null);
        if( lstEntPubToTop.size() == 1 )
          lstUnpublish.add( lstEntPubToTop.get(0) );
      }
      break;

        // there is more that one
      default:
        m_logger.severe("More than one organization found for the ID: " +
                        rs.getString(MGID));
      }
    }
    stmt.close();
    conn.close();

    // do publish
    if( lstEntities.size() > 0 && srv.addEntityPublications(lstEntities).size() == lstEntities.size() )
      m_logger.fine( "Roles published successfully" );
    
    m_logger.finest("List to unpublish: " + lstUnpublish.toString());
    if( srv.removeEntityPublications(lstUnpublish) )
      m_logger.fine( "Roles successfully unpublished" );
    
    m_logger.exiting(TAG, "execute");
  }

  public HashMap getAttributes() {
    return new HashMap();
  }

  public void setAttributes() {
  }
  
  private RoleManagerResult createRole( String strName ) throws Exception {
    m_logger.entering(TAG, "createRole");
    RoleManager mgr = Platform.getService(RoleManager.class);
    
    HashMap<String,Object> map = new HashMap<String,Object>();
    
    map.put(RoleManagerConstants.RoleAttributeName.NAME.getId(), strName);
    Role role = new Role(map);
    
    RoleManagerResult res = mgr.create(role);
    
    m_logger.exiting(TAG, "createRole");
    return res;
  }
  
  private boolean isRolePublishedToOrg( String strRoleKey, String strOrgKey ) {
    m_logger.entering( TAG, "isRolePublishedToOrg", strRoleKey + " " + strOrgKey );
    EntityPublicationService srvEntPub = Platform.getService(EntityPublicationService.class);
    
    boolean bRes = false;
    List<EntityPublication> lstPubs = srvEntPub.listEntityPublicationsInScope(PolicyConstants.Resources.ROLE, strOrgKey, null);
    for( EntityPublication ent : lstPubs )
      if( ent.getEntityId().equals(strRoleKey) ) {
        bRes = true;
        break;
      }
    m_logger.exiting( TAG, "isRolePublishedToOrg", bRes );
    return bRes;
  }
}
