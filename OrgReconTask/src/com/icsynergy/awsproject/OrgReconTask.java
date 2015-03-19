package com.icsynergy.awsproject;


import com.icsynergy.helpers.ITResHelper;
import com.icsynergy.helpers.JDBCHelper;
import com.icsynergy.helpers.LookupHelper;
import com.icsynergy.helpers.SysConfigHelper;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import oracle.iam.identity.exception.NoSuchRoleException;
import oracle.iam.identity.exception.NoSuchUserException;
import oracle.iam.identity.exception.OrganizationManagerException;
import oracle.iam.identity.exception.RoleAlreadyExistsException;
import oracle.iam.identity.exception.RoleCreateException;
import oracle.iam.identity.exception.RoleLookupException;
import oracle.iam.identity.exception.RoleMemberException;
import oracle.iam.identity.exception.RoleModifyException;
import oracle.iam.identity.exception.RoleSearchException;
import oracle.iam.identity.exception.SearchKeyNotUniqueException;
import oracle.iam.identity.exception.UserModifyException;
import oracle.iam.identity.exception.ValidationFailedException;
import oracle.iam.identity.orgmgmt.api.OrganizationManager;
import oracle.iam.identity.orgmgmt.api.OrganizationManagerConstants;
import oracle.iam.identity.orgmgmt.vo.Organization;
import oracle.iam.identity.rolemgmt.api.RoleManager;
import oracle.iam.identity.rolemgmt.api.RoleManagerConstants;
import oracle.iam.identity.rolemgmt.vo.Role;
import oracle.iam.identity.rolemgmt.vo.RoleManagerResult;
import oracle.iam.identity.usermgmt.api.UserManager;
import oracle.iam.identity.usermgmt.api.UserManagerConstants;
import oracle.iam.identity.usermgmt.vo.User;
import oracle.iam.notification.api.NotificationService;
import oracle.iam.notification.vo.NotificationEvent;
import oracle.iam.platform.Platform;
import oracle.iam.platform.authopss.api.PolicyConstants;
import oracle.iam.platform.authopss.vo.EntityPublication;
import oracle.iam.platform.entitymgr.vo.SearchCriteria;
import oracle.iam.platform.entitymgr.vo.SearchRule;
import oracle.iam.platformservice.api.EntityPublicationService;
import oracle.iam.scheduler.vo.TaskSupport;


public class OrgReconTask extends TaskSupport {
  private static final String TAG = "OrgReconTask 0.9.6";
  private final static Logger m_logger = Logger.getLogger("com.icsynergy");
  
  // task parameter names
  private static final String PARAM_TNAME = "Table Name";
  private static final String PARAM_FILTER = "ID List To Filter Out";
  private static final String PARAM_DEFADMROLENAME = "Default Admin Role";

  // table name fields
  private static final String MG = "MANAGEMENT_GROUP";
  private static final String MGPIN = "MANAGEMENT_GROUP_PIN";
  private static final String MGID = "MANAGEMENT_GROUP_ID";
  private static final String strSQLTemplate =
    "SELECT %s, %s, %s FROM %s WHERE %s NOT IN (%s) GROUP BY %s, %s, %s";

  // system variable to read IT resource name containing DB connection params from
  private static final String SYSVARCODE = "AWS.DBITResName";
	private static final String CONFIGLOOKUP = "Lookup.AWS.Configuration";
	private static final String CONFIGORGCREATEUSERTONOTIFY = "org.create.user.notify";
	private static final String CONFIGORGCREATETEMPLATE = "org.create.notification.template";

	// custom role modifiers
	private static final String ROLENAMEPREFIX = "aws_delegated_admin_";
	private static final String ROLENAMESUFFIX = "Admin";
  
  // Organization UDFs
  private static final String UDFPIN = "pin";
  private static final String UDFGRPID = "grp_id";
  
  // User UDF
  private static final String UDFGRPSID = "AWSMgmtGrpIDs";
  
  // Services
	private final UserManager usrMgr = Platform.getService(UserManager.class);
  private final OrganizationManager orgMgr = 
    Platform.getService(OrganizationManager.class);
  private final RoleManager roleMgr = Platform.getService(RoleManager.class);
	private final NotificationService srvNotification = 
		Platform.getService(NotificationService.class);

  public void execute(HashMap hashMap) throws Exception {
    m_logger.entering(TAG, "execute", hashMap.toString());

    JDBCHelper helper = null;

    String strITResName = SysConfigHelper.getPropValue(SYSVARCODE);
    if (strITResName == null)
      throw new Exception("Can't get a value for a system property: " +
                          SYSVARCODE);

    // pull table name from a param
    String strTableName = hashMap.get(PARAM_TNAME).toString();

    // check filter param
    String strFilterList =
      hashMap.containsKey(PARAM_FILTER) ? hashMap.get(PARAM_FILTER).toString() :
                                          null;
    
    // default admin role name
    String strDefaultAdminRoleName = hashMap.get(PARAM_DEFADMROLENAME).toString();

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

    if (helper == null)
      throw new Exception("Can't get a JDBC helper instance");
    
    Connection conn = helper.getConnection();
    if (conn == null)
      throw new Exception("Can't get a connection to DB");

		if (roleMgr == null)
			throw new Exception("Can't get role manager interface");

		if (srvNotification == null)
			throw new Exception("Can't get Notification interface");
		
		if (usrMgr == null)
			throw new Exception("Can't get user manager interface");

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
    List<EntityPublication> lstEntities = new ArrayList<>();
    // ... to unpublish
    List<EntityPublication> lstUnpublish = new ArrayList<>();

    // get ID for organization "Top"
    Set<String> setAttrs = new HashSet<>();
    setAttrs.add(OrganizationManagerConstants.AttributeName.ID_FIELD.getId());
    
    Organization org = orgMgr.getDetails("Top", setAttrs, true);
    String strTopOrgId = org.getEntityId();
    
    // get ID of Default Admin role
    setAttrs.clear();
    setAttrs.add(RoleManagerConstants.RoleAttributeName.KEY.getId());
    Role roleDefaultAdmin = roleMgr.getDetails(
                            RoleManagerConstants.RoleAttributeName.NAME.getId(), 
                            strDefaultAdminRoleName, setAttrs);
    String strDefaultAdminRoleKey = roleDefaultAdmin.getEntityId();

    // query DB view and cycle through records
    try (
      Statement stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery(SQL)) 
    {    
      // cycle through all DB records
      while (rs.next()) {
  
        // Set search criteria to orgs with the specified Management Group ID
        SearchCriteria crit =
          new SearchCriteria(UDFGRPID, rs.getInt(MGID), SearchCriteria.Operator.EQUAL);
  
        // return attributes set
        setAttrs.clear();
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
          // try to search also by name
          String strDuplicateNameOrgKey = 
            findOrgByName(rs.getString(MG));
          
          // if found re-ID it
          if (!strDuplicateNameOrgKey.isEmpty()
          && !changeOrgID(strDuplicateNameOrgKey, new Random().nextInt())) {
            m_logger.severe("Can't re-ID existing organization with the same name: "
                            + rs.getString(MG));
            break;
          }
          
          // if not found -> create with following attributes
          org = new Organization();
          org.setAttribute(OrganizationManagerConstants.
                           AttributeName.ORG_NAME.getId(), rs.getString(MG));
          org.setAttribute(OrganizationManagerConstants.
                           AttributeName.ORG_TYPE.getId(), "Company");
          org.setAttribute(UDFPIN, rs.getString(MGPIN));
          org.setAttribute(UDFGRPID, rs.getInt(MGID));
  
          // create organization
          String strStatus = orgMgr.create(org);
          m_logger.fine("Organization created: " + rs.getString(MG) +
                        ". Status: " + strStatus);
          
          // send notification (all logging is inside)
          if (sendOrgCreateNotification(rs.getString(MG))) {
            m_logger.fine("Notification has been sent");
          } else {
            m_logger.severe("Error sending the notification");
          }
  
          /****************** ROLES section ***********************************/
          // search for a role with a name aws_delegated_admin + Management Group ID
          crit = 
            new SearchCriteria(RoleManagerConstants.RoleAttributeName.NAME.getId(),
                               ROLENAMEPREFIX + rs.getString(MGID),
                               SearchCriteria.Operator.EQUAL);
          setAttrs.clear();
          // return just key att
          setAttrs.add(RoleManagerConstants.RoleAttributeName.KEY.getId());
          List<Role> lstRole = roleMgr.search(crit, setAttrs, null);
          
          // if role found - severe problem, report and go next, do nothing
          if (lstRole.size() > 0) {
            m_logger.severe("There is an existing role name = " +
                            ROLENAMEPREFIX + rs.getString(MGID) +
                            " for a new organization = " +
                            rs.getString(MG) + ". Check data!");
            break;
          }
          
          // create role {{RoleName=aws_delegated_admin_<GRPID>},{RoleDsplName=MG+Admin}}
          HashMap<String,Object> hmap = new HashMap<String,Object>();
          hmap.put(RoleManagerConstants.RoleAttributeName.NAME.getId(), 
                   ROLENAMEPREFIX + rs.getString(MGID));        
          hmap.put(RoleManagerConstants.RoleAttributeName.DISPLAY_NAME.getId(), 
                   rs.getString(MG) + " " + ROLENAMESUFFIX);
  
          Role role = new Role(hmap);
          RoleManagerResult rmResult = roleMgr.create(role);
          m_logger.fine("Role " + rs.getString(MG) + " created with id:" + rmResult.getEntityId());
          
          // set hierarchy
          roleMgr.addRoleRelationship(strDefaultAdminRoleKey, 
                                      rmResult.getEntityId());
  
          // entity to publish
          EntityPublication entPub = getEPubForRoleToOrg( rmResult.getEntityId(), strStatus );
          lstEntities.add(entPub);
          
          // get a list of publications of the role, for a newly created role it's only Top Org
          List<EntityPublication> lstPublications = srv.listEntityPublications(PolicyConstants.Resources.ROLE, rmResult.getEntityId(), null);
  
          if( lstPublications.size() == 1 )
            lstUnpublish.add(lstPublications.get(0));
        
        /* --------------- Role with a name of an org -----------------------------*/
          // search for a role with description equal to Management Group ID
          crit = 
            new SearchCriteria(RoleManagerConstants.RoleAttributeName.DESCRIPTION.getId(),
                               rs.getString(MGID), 
                               SearchCriteria.Operator.EQUAL);
          setAttrs.clear();
          setAttrs.add(RoleManagerConstants.RoleAttributeName.KEY.getId());
          lstRole = roleMgr.search(crit, setAttrs, null);
          
          // report problem if something is found
          if (lstRole.size() > 0) {
            m_logger.severe("There is an existing role with description = " +
                            rs.getString(MGID) +
                            " for a new organization = " +
                            rs.getString(MG) + ". Check data!");
            break;
          }
          
          // create role {{RoleName=MG},{RoleDesc=MGID}} 
          // with membership rule
          rmResult = createRoleWOrgName(rs.getString(MG), rs.getString(MGID));
        
          // entity to publish
          entPub = getEPubForRoleToOrg( rmResult.getEntityId(), strStatus );
          // add to the list for publishing
          lstEntities.add( entPub );
        
          // get a list of publications of the role, for a newly created role it's only Top Org
          lstPublications = srv.listEntityPublications(PolicyConstants.Resources.ROLE, rmResult.getEntityId(), null);
  
          if( lstPublications.size() == 1 )
            lstUnpublish.add(lstPublications.get(0));
          
          break;
  
  
        ///////////////////////////////////////////// ONE ORGANIZATION FOUND ////////////
        case 1:
          org = listOrgs.get(0);
          
          boolean bChangeName = false;
          
          //if organization is not Active -> skip it
          if (! OrganizationManagerConstants.AttributeValue 
            .ORG_STATUS_ACTIVE.getId()
            .equalsIgnoreCase(org
                              .getAttribute(OrganizationManagerConstants 
                                            .AttributeName.ORG_STATUS.getId())
                              .toString())) 
            break;
          
          // if organization name is different from the query 
          if (!org.getAttribute(OrganizationManagerConstants.
                                            AttributeName.ORG_NAME.getId()).toString().
              equalsIgnoreCase(rs.getString(MG))) {
            
            // re-id existing organization
            if (!changeOrgID(org.getEntityId(), new Random().nextInt())) 
              m_logger.severe("Can't re-ID organization: " + listOrgs);
            
            //reconciled org will be created on next task run
            break;
          }
  
          // if PIN has been changed -> set the flag
          if (! org
                .getAttribute(UDFPIN).toString()
                .equalsIgnoreCase(rs.getString(MGPIN))) {
            org = new Organization(org.getEntityId());
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
                            " exists in OIM and has all the same attributes. Checking roles");
        
          /****************** ROLES section ***********************************/  
          // search for a role name = aws_delegated_admin_MGID
          setAttrs.clear();
          setAttrs.add(RoleManagerConstants.RoleAttributeName.KEY.getId());
          setAttrs.add(RoleManagerConstants.RoleAttributeName.DISPLAY_NAME.getId());
          crit = new SearchCriteria(RoleManagerConstants.RoleAttributeName.NAME.getId(), 
                                    ROLENAMEPREFIX + rs.getString(MGID), 
                                    SearchCriteria.Operator.EQUAL);
          lstRole = roleMgr.search(crit, setAttrs, null);
          
          boolean bPublish = false;
          String strRoleKey = null;
          
          // if role not found -> create it
          if( lstRole.size() == 0 ){
            m_logger.finest( "Role hasn't been found: " + ROLENAMEPREFIX + rs.getString(MGID) );
            
            // create role {{RoleName=aws_delegated_admin_<GRPID>},{RoleDsplName=MG+Admin}}
            hmap = new HashMap<String,Object>();
            hmap.put(RoleManagerConstants.RoleAttributeName.NAME.getId(), 
                     ROLENAMEPREFIX + rs.getString(MGID));        
            hmap.put(RoleManagerConstants.RoleAttributeName.DISPLAY_NAME.getId(), 
                     rs.getString(MG) + " " + ROLENAMESUFFIX);
            
            role = new Role(hmap);
            rmResult = roleMgr.create(role);        
            m_logger.fine("Role " + ROLENAMEPREFIX + rs.getString(MGID) + 
                          " created with id:" + rmResult.getEntityId());
            
            // set hierarchy
            roleMgr.addRoleRelationship(strDefaultAdminRoleKey, 
                                        rmResult.getEntityId());
    
            // if role successfully created -> publish it to org
            bPublish = rmResult.getStatus().equalsIgnoreCase("COMPLETED");
            strRoleKey = rmResult.getEntityId();
            
            
            // role found -> change/set display name if required
          } else if (lstRole.size() == 1) { 
            m_logger.finest("Role " + ROLENAMEPREFIX + rs.getString(MGID) + 
                            " exists");
            strRoleKey = lstRole.get(0).getEntityId();
            
            String strDisplayName = rs.getString(MG) + " " + ROLENAMESUFFIX;
            // set bChangeName if role display name != proper one (transition case)
            bChangeName |= !strDisplayName.equals(lstRole.get(0).getDisplayName());
            
            if (bChangeName) {
              role = new Role(strRoleKey);
              role.setDisplayName(rs.getString(MG) + " " + ROLENAMESUFFIX);
              rmResult = roleMgr.modify(role);
              
              assert !rmResult.getStatus().equalsIgnoreCase("COMPLETED");
              m_logger.finest("Role display name has been set to: " + 
                              rs.getString(MG) + " " + ROLENAMESUFFIX);
            }
            
            // check if hierarchy is set
            boolean bSetHierarchy = 
              !roleMgr.isRoleParent(strDefaultAdminRoleKey, strRoleKey, true);
            
            // set if required
            if (bSetHierarchy) {
              rmResult = roleMgr.addRoleRelationship(strDefaultAdminRoleKey, 
                                          strRoleKey);
              assert !rmResult.getStatus().equalsIgnoreCase("COMPLETED");
              m_logger.finest("Hierarchy for role: " + 
                              ROLENAMEPREFIX + rs.getString(MGID) + " set");
            }
            
            bPublish = !isRolePublishedToOrg( strRoleKey, 
                                              listOrgs.get(0).getEntityId() );
          } else {
            m_logger.severe("More than one role found with a name: " + 
                            ROLENAMEPREFIX + rs.getString(MGID));
            break;
          }
          
          // publish if required
          if( bPublish ) {
            m_logger.finest("Role is required to be published to the org");
            entPub = getEPubForRoleToOrg( strRoleKey, listOrgs.get(0).getEntityId() );        
            lstEntities.add( entPub );
            m_logger.finest("Role added for publication");
          }
          // get EntPub for the role publication to Top
          List<EntityPublication> lstEntPubToTop 
            = srv.listEntityPublicationInScope(PolicyConstants.Resources.ROLE, 
                                               strRoleKey, strTopOrgId, false, null);
          if( lstEntPubToTop.size() == 1 )
            lstUnpublish.add( lstEntPubToTop.get(0) );
      
          /* ----------------------- for a Role with the name of Org ------------------------------- */
          // search for a role with description equal to Management Group ID 
          // or RoleName == OrganizationName 
          SearchCriteria critDesc = 
            new SearchCriteria(RoleManagerConstants.RoleAttributeName.DESCRIPTION.getId(),
                               rs.getString(MGID), SearchCriteria.Operator.EQUAL);
          SearchCriteria critName = 
            new SearchCriteria(RoleManagerConstants.RoleAttributeName.NAME.getId(),
                               rs.getString(MG), SearchCriteria.Operator.EQUAL);
          crit = new SearchCriteria(critDesc, critName, 
                                    SearchCriteria.Operator.OR);
          setAttrs.clear();
          setAttrs.add(RoleManagerConstants.RoleAttributeName.KEY.getId());
          setAttrs.add(RoleManagerConstants.RoleAttributeName.DESCRIPTION.getId());
          lstRole = roleMgr.search(crit, setAttrs, null);
          
          bPublish = false;
          strRoleKey = null;
          
          // if role not found -> create it and set membership rule
          if( lstRole.size() == 0 ){
            m_logger.finest( "Role hasn't been found: " + rs.getString(MG) );
            rmResult = createRoleWOrgName(rs.getString(MG), rs.getString(MGID));
            
            // if role successfully created -> publish it to org
            bPublish = rmResult.getStatus().equalsIgnoreCase("COMPLETED");
            strRoleKey = rmResult.getEntityId();
            

          } else if( lstRole.size() == 1) {
            // role found -> change role name and/or description if required
            // check for a membership rule also
            m_logger.finest("Role " + rs.getString(MG) + " exists");
            strRoleKey = lstRole.get(0).getEntityId();
            
            // set role description if it's not equal(empty) to MGID
            boolean bSetDescription = 
              !rs.getString(MGID).equals(lstRole.get(0).getDescription());
            
            if (bChangeName || bSetDescription) {
              role = new Role(strRoleKey);
              
              if (bChangeName) 
                role.setName(rs.getString(MG));
              
              if (bSetDescription)
                role.setDescription(rs.getString(MGID));
              
              rmResult = roleMgr.modify(role);
              if (rmResult.getStatus().equalsIgnoreCase("COMPLETED")) {
                m_logger.finest("Role " + rs.getString(MG) + " modified");
              } else {
                m_logger.warning("Can't modify role " + rs.getString(MG));
              }            
            }
            
            m_logger.finest("Checking role's membership rule...");
            // check membership rule and set if required
            SearchRule rule = 
              roleMgr.getUserMembershipRule(strRoleKey);
            
            if (rule == null) {
              rmResult = setMembershipRule(strRoleKey, 
                                           rs.getString(MG));
            }
  
            
            bPublish = !isRolePublishedToOrg( strRoleKey, 
                                              listOrgs.get(0).getEntityId() );
          } else {
            m_logger.severe("More than one role found with a name: " + 
                            rs.getString(MG));
            break;
          }
          
          // publish if required
          if( bPublish ) {
            m_logger.finest("Role is required to be published to the org");
            entPub = getEPubForRoleToOrg( strRoleKey, listOrgs.get(0).getEntityId() );        
            lstEntities.add( entPub );
            m_logger.finest("Role added for publication");        
          }
          // get EntPub for the role publication to Top
          lstEntPubToTop = 
            srv.listEntityPublicationInScope(PolicyConstants.Resources.ROLE, 
                                             strRoleKey, strTopOrgId, false, null);
          if( lstEntPubToTop.size() == 1 )
            lstUnpublish.add( lstEntPubToTop.get(0) );
    
          break;
  
        // there is more that one organization
        default:
          m_logger.severe("More than one organization found for the ID: " +
                          rs.getString(MGID));
        }
      }
    }

    // do publish
    if( lstEntities.size() > 0 && srv.addEntityPublications(lstEntities).size() == lstEntities.size() )
      m_logger.fine( "Roles published successfully" );
    
    // do unpublish
    if( srv.removeEntityPublications(lstUnpublish) )
      m_logger.fine( "Roles successfully unpublished" );
    
    m_logger.exiting(TAG, "execute");
  }

  /** Checks if a role is published to an organization
   * @param strRoleKey Role key to check
   * @param strOrgKey Org key to check
   * @return true if the role is published to the org
   */
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

  /** Creates an EntityPublication for a given role to a given organization
   * @param strRoleKey Role to publish
   * @param strOrgKey Organization to publish the role to
   * @return EntityPublication object to publish
   */
  private EntityPublication getEPubForRoleToOrg( String strRoleKey, String strOrgKey ) {
    m_logger.entering(TAG, "getEPubForRoleToOrg");
    
    EntityPublication res = new EntityPublication();
    
    res.setEntityId( strRoleKey );
    res.setScopeId( strOrgKey );
    res.setHierarchicalScope( true );
    res.setEntityType( PolicyConstants.Resources.ROLE.getId() );
    
    m_logger.exiting(TAG, "getEPubForRoleToOrg");
    return res;
  }

  /** Helper method to create a role with an organization name and set its 
   * description to MG id
   * @param strMG Management Group name
   * @param strMGID Management Group id
   * @return RoleManagerResult
   * @throws ValidationFailedException
   * @throws RoleAlreadyExistsException
   * @throws RoleCreateException
   * @throws RoleModifyException
   * @throws NoSuchRoleException
   */
  private RoleManagerResult createRoleWOrgName(String strMG, String strMGID) throws ValidationFailedException,
                                                                      RoleAlreadyExistsException,
                                                                      RoleCreateException,
                                                                      RoleModifyException,
                                                                      NoSuchRoleException {
    m_logger.entering(TAG, "createRoleWOrgName", strMG + " " + strMGID);
    
    // set Name to MG, Description to MGID
    HashMap<String,Object> hmap = new HashMap<String,Object>();
    hmap.put(RoleManagerConstants.RoleAttributeName.NAME.getId(), strMG);        
    hmap.put(RoleManagerConstants.RoleAttributeName.DESCRIPTION.getId(), strMGID);
    Role role = new Role(hmap);
    RoleManagerResult rmResult = roleMgr.create(role);

    if (rmResult.getStatus().equalsIgnoreCase("COMPLETED")) {
      m_logger.fine("Role " + strMG + " created");
    } else {
      m_logger.warning("Can't create role " + strMG);
    }
    
    // set membership rule
    rmResult = setMembershipRule(rmResult.getEntityId(), strMG);
    
    m_logger.exiting(TAG, "createRoleWOrgName", rmResult.toString());
    return rmResult;
  }

  /** Sets a membership rule for a role to User.organization = Organization name
   * @param strRoleKey Key for a role to set rule for
   * @param strOrgName Organization name
   * @return RoleManagerResult of the operation
   * @throws ValidationFailedException
   * @throws RoleModifyException
   * @throws NoSuchRoleException
   */
  private RoleManagerResult setMembershipRule(String strRoleKey, 
                                              String strOrgName) throws ValidationFailedException,
                                                      RoleModifyException,
                                                      NoSuchRoleException {
    m_logger.entering(TAG, "setMembershipRule", strRoleKey + " " + strOrgName);
    
    SearchRule rule = 
      new SearchRule(UserManagerConstants.AttributeName.USER_ORGANIZATION.getId(), 
                      strOrgName, SearchRule.Operator.EQUAL);
    RoleManagerResult rmResult = roleMgr.setUserMembershipRule(strRoleKey, rule);

    if (rmResult.getStatus().equalsIgnoreCase("COMPLETED")) {
      m_logger.fine("Membership rule for role " + strOrgName + " has been set");
    } else {
      m_logger.warning("Can't set a membership rule for role " + strOrgName);
    }
    
    m_logger.exiting(TAG, "setMembershipRule", rmResult.toString());
    return rmResult;
  }

  /**
   * Sends notification of an organization creation
   * @param strOrgName
   * @return
   * @throws Exception
   */
  private boolean sendOrgCreateNotification (String strOrgName) throws Exception {
		m_logger.entering(TAG, "sendOrgCreateNotification", strOrgName);
		
		boolean bRet = false;
		LookupHelper helper = 
			LookupHelper.getLookupHelper(CONFIGLOOKUP);
		
		if (helper == null)
			throw new Exception("Can't get the configuration lookup");
		
		String strUsrLogin = 
			helper.getLookupValueForEncoded(CONFIGORGCREATEUSERTONOTIFY);
		if (strUsrLogin == null)
			throw new Exception("User to notify on org creation is not set");
		m_logger.fine("User to notify: " + strUsrLogin);

		// check if the user, specified in the lookup, exists in OIM
		// if not an Exception is thrown
		usrMgr.getDetails(strUsrLogin, null, true);
		
		String[] arTo = {strUsrLogin};

		String strTemplate = 
			helper.getLookupValueForEncoded(CONFIGORGCREATETEMPLATE);
		if (strTemplate == null || strTemplate.length() == 0)
			throw new Exception("Notification template for organization creation " +
				"is not set");
		
		NotificationEvent event = new NotificationEvent();
		event.setTemplateName(strTemplate);
		event.setUserIds(arTo);
    event.setSender(null);
		
		// attach data
	  HashMap<String,Object> map = new HashMap<>();
		map.put("org_name", strOrgName);
		event.setParams(map);
		
		bRet = srvNotification.notify(event);
		
	  m_logger.exiting(TAG, "sendOrgCreateNotification", bRet);
		return bRet;
	}
  
  /** Searches for Organization by name
   * @param strOrgName key to search
   * @return Organization key if found only one, "" if no orgs found
   */
  private String findOrgByName(String strOrgName) {
    m_logger.entering(TAG, "findOrgByName", strOrgName);
    
    String strRet = "";
    SearchCriteria crit = 
      new SearchCriteria(OrganizationManagerConstants.AttributeName.ORG_NAME.getId(), 
                         strOrgName, SearchCriteria.Operator.EQUAL);
    
    // return set of attributes
    Set<String> set = new HashSet<>();
    set.add(OrganizationManagerConstants.AttributeName.ID_FIELD.getId());
    
    List<Organization> lst = null;
    try {
      lst = orgMgr.search(crit, set, null);
    } catch (OrganizationManagerException e) {
      m_logger.log(Level.SEVERE, "Exception searching for org", e);
      return strRet;
    }
    
    // if only one org found -> return its key
    if (lst.size() == 1) {
      strRet = lst.get(0).getEntityId();
    }
    
    m_logger.exiting(TAG, "findOrgByName", strRet);
    return strRet;
  }
  
  /** Changes organization management group id 
   * and roles related to an org according to new org GRP_ID
   * @param strOrgKey Organization key
   * @param iToID new GRP_ID
   * @return true if all changes were successful
   */
  private boolean changeOrgID(String strOrgKey, int iToID) {
    m_logger.entering(TAG, "changeOrgID", strOrgKey + ":" + iToID);
    boolean bRet = false;
    
    Organization org = null;
    try {
      org = orgMgr.getDetails(strOrgKey, null, false);
    } catch (OrganizationManagerException e) {
      m_logger.log(Level.SEVERE, "Exception pulling org attributes");
      return bRet;
    }
    
    final String strOldGrpId = org.getAttribute(UDFGRPID).toString();
    // find and change aws_admin role
    SearchCriteria crit = 
      new SearchCriteria(RoleManagerConstants.RoleAttributeName.NAME.getId(),
                         "aws_delegated_admin_" + strOldGrpId, 
                         SearchCriteria.Operator.EQUAL);
    List<Role> lst = null;
    try {
      lst = roleMgr.search(crit, null, null);
    } catch (RoleSearchException e) {
      m_logger.log(Level.SEVERE, "Exception searching for a role", e);
      return bRet;
    }
    
    if (lst.size() == 1) {
      Role role = new Role(lst.get(0).getEntityId());
      role.setName("aws_delegated_admin_" + iToID);
      try {
        roleMgr.modify(role);
        m_logger.info("admin role has been modified");
      } catch (Exception e) {
        m_logger.log(Level.SEVERE, "Can't change role name", e);
        return bRet;
      }
    } else if (lst.size() == 0){
      m_logger.warning("No aws_admin role found");
    } else {
      m_logger.severe("More than one admin role found!");
    }

    // find and change role with org name
    crit = 
      new SearchCriteria(RoleManagerConstants.RoleAttributeName.NAME.getId(),
                         org.getAttribute(OrganizationManagerConstants.AttributeName.ORG_NAME.getId()).toString(), 
                         SearchCriteria.Operator.EQUAL);
    try {
      lst = roleMgr.search(crit, null, null);
    } catch (RoleSearchException e) {
      m_logger.log(Level.SEVERE, "Exception searching for a role", e);
    }
    
    if (lst.size() == 1) {
      Role role = new Role(lst.get(0).getEntityId());
      role.setDescription(Integer.toString(iToID));
      try {
        roleMgr.modify(role);
        m_logger.info("Main role has been modified");
      } catch (Exception e) {
        m_logger.log(Level.SEVERE, "Can't change role name", e);
      }
    } else if (lst.size() == 0){
      m_logger.warning("No role found with description = " + 
                       org.getAttribute(UDFGRPID).toString());
    } else {
      m_logger.severe("More than one admin role found!");
    }

    // change GRP_ID
    org = new Organization(strOrgKey);
    org.setAttribute(UDFGRPID, iToID);

    try {
      orgMgr.modify(org);
      m_logger.info("Org ID has been successfully changed");
    } catch (OrganizationManagerException e) {
      m_logger.log(Level.SEVERE, "Exception modifying org", e);
    }

    bRet = changeGrpIdUDF(strOrgKey, strOldGrpId, Integer.toString(iToID));
    
    m_logger.exiting(TAG, "changeOrgID", bRet);
    return bRet;
  }
  
  /**
   * Changes users' MgmtGrpIds UDF according to a new Grp Id
   * @param strOrgKey Organization where to find users for the change
   * @param strOldGrpId Old Management Group ID
   * @param strNewGrpId New Management Group ID
   * @return
   */
  final boolean changeGrpIdUDF(String strOrgKey, String strOldGrpId, String strNewGrpId) {
    m_logger.entering(TAG, "changeGrpIdUDF", 
                      String.format("org: %s from_id: %s to_id: %s", 
                                    strOrgKey, strOldGrpId, strNewGrpId));
    boolean bRet = false;
    
    // get organization name
    Organization org;
    try {
      org = orgMgr.getDetails(strOrgKey, null, null);
    } catch (SearchKeyNotUniqueException | OrganizationManagerException e) {
      m_logger.log(Level.SEVERE,
                   "Exception searching for organization detail for org_key="
                      + strOrgKey, e);
      return bRet;
    }
    
    // get Role id
    Role role;
    try {
      role =
          roleMgr.getDetails(RoleManagerConstants
                         .RoleAttributeName.DISPLAY_NAME.getId(),
                         org.getAttribute(OrganizationManagerConstants
                                          .AttributeName.ORG_NAME
                                          .getId()).toString(), null);
    } catch (RoleLookupException 
             | NoSuchRoleException 
             | SearchKeyNotUniqueException e) {
      m_logger.log(Level.SEVERE,
                   "Exception searching for role with display name: "
                      + org.getAttribute(OrganizationManagerConstants
                                        .AttributeName.ORG_NAME
                                        .getId()).toString(), e);
      return bRet;
    }
    
    // get a list of users with this role
    List<User> lstUsers;
    try {
      lstUsers = roleMgr.getRoleMembers(role.getEntityId(), true);
    } catch (RoleMemberException e) {
      m_logger.log(Level.SEVERE, 
                   "Exception searching for users with a role_key: "
                   + role.getEntityId());
      return bRet;
    }
    
    // cycle through all the users and replace Old_Grp_Id with New_Grp_Id
    // doesnt' check Group Name UDF
    for (User usr : lstUsers) {
      String str = usr.getAttribute(UDFGRPSID).toString();
      String[] arIds = {""};
      if (!str.isEmpty()) {
        arIds = str.split(",");
        
        // search for Old Grp Id and replace it 
        for (int i = 0; i < arIds.length; i++) {
          if (arIds[i].equalsIgnoreCase(strOldGrpId)) {
            arIds[i] = strNewGrpId;
            break;
          }
        }
      } else {
        arIds[0] = strNewGrpId;
      }
      
      // join array back into a string
      str = 
        Arrays.toString(arIds).replaceAll(", ", ",").replaceAll("[\\[\\]]", "");
      m_logger.finest("Setting user's UDF to: " + str);
      
      // set user's attribute
      User usrToBe = new User(usr.getEntityId());
      usr.setAttribute(UDFGRPSID, str);
      try {
        usrMgr.modify(usrToBe);
        m_logger.fine("Attribute " + UDFGRPSID + 
                      " for user key: " + usr.getEntityId() +
                      " successfully set to: " + str);
      } catch (NoSuchUserException 
               | UserModifyException 
               | ValidationFailedException e) {
        m_logger.log(Level.WARNING, "Exception setting " + UDFGRPSID 
                                  +" attribute to: " + str, e);
      }
    }
    
    m_logger.exiting(TAG, "changeGrpIdUDF");
    return true;
  }

  public HashMap getAttributes() {
    return new HashMap();
  }

  public void setAttributes() {
  }
}
