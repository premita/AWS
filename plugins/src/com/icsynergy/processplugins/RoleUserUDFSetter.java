package com.icsynergy.processplugins;


import com.icsynergy.helpers.csf.CsfAccessor;

import java.io.Serializable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import oracle.iam.conf.api.SystemConfigurationService;
import oracle.iam.identity.exception.OrganizationManagerException;
import oracle.iam.identity.orgmgmt.api.OrganizationManager;
import oracle.iam.identity.orgmgmt.api.OrganizationManagerConstants;
import oracle.iam.identity.orgmgmt.vo.Organization;
import oracle.iam.identity.rolemgmt.api.RoleManager;
import oracle.iam.identity.rolemgmt.api.RoleManagerConstants;
import oracle.iam.identity.rolemgmt.vo.Role;
import oracle.iam.identity.usermgmt.api.UserManager;
import oracle.iam.identity.usermgmt.api.UserManagerConstants;
import oracle.iam.identity.usermgmt.vo.User;
import oracle.iam.platform.OIMClient;
import oracle.iam.platform.Platform;
import oracle.iam.platform.authopss.api.AdminRoleService;
import oracle.iam.platform.authopss.vo.AdminRole;
import oracle.iam.platform.authopss.vo.AdminRoleMembership;
import oracle.iam.platform.context.ContextManager;
import oracle.iam.platform.entitymgr.vo.SearchCriteria;
import oracle.iam.platform.kernel.spi.PostProcessHandler;
import oracle.iam.platform.kernel.vo.AbstractGenericOrchestration;
import oracle.iam.platform.kernel.vo.BulkEventResult;
import oracle.iam.platform.kernel.vo.BulkOrchestration;
import oracle.iam.platform.kernel.vo.EventResult;
import oracle.iam.platform.kernel.vo.Orchestration;

import oracle.security.jps.service.credstore.PasswordCredential;


public class RoleUserUDFSetter implements PostProcessHandler {
  private final static Logger logger = Logger.getLogger("com.icsynergy");
  private final static String TAG = RoleUserUDFSetter.class.getCanonicalName();
  
  private final static String OIMURL = "AWS.OIMURL";
  
  public EventResult execute(long l, long l2, Orchestration orchestration) {
    logger.entering(TAG, "execute");
    
    Map<String,Serializable> map = orchestration.getParameters();
    logger.finest("Orch params: " + map.toString());

    List<String> lstUsrKeys = (List<String>) map.get("userKeys");

    Set<String> setRoleKeys = new HashSet<>();
    if (map.containsKey("roleKey")) {
      String strRoleKey = map.get("roleKey").toString();
      setRoleKeys.add(strRoleKey);
    } 
    else if (map.containsKey("roleKeys")) {
      setRoleKeys = new HashSet<>((List<String>) map.get("roleKeys"));
    }

    logger.finest("User Keys: " + lstUsrKeys.toString());
    logger.finest("Role keys: " + setRoleKeys.toString());
    
    UserManager usrMgr;
    RoleManager roleMgr;
    OrganizationManager orgMgr;
    
    // if REQUEST context -> login and get all manager classes
    if ("<anonymous>".equalsIgnoreCase(ContextManager.getOrigUser())) {
      OIMClient oimClient = null;  
      // log into the system    
      try {
        logger.finest("Logging in...");
        PasswordCredential creds = CsfAccessor.readCredentialsfromCsf("oim", "sysadmin");
          
        String strOimUserName = creds.getName();
        String strOimPassword = new String(creds.getPassword());
  
        SystemConfigurationService cfgServ = 
          Platform.getService(SystemConfigurationService.class);
        String strURL =
          cfgServ.getSystemPropertiesForUnauthenticatedUsers(OIMURL)
          .getPtyValue();
        
        Hashtable env = new Hashtable();
        env.put(OIMClient.JAVA_NAMING_FACTORY_INITIAL, "weblogic.jndi.WLInitialContextFactory");
        env.put(OIMClient.JAVA_NAMING_PROVIDER_URL, strURL);
        oimClient = new OIMClient(env);    
        
        oimClient.login(strOimUserName, strOimPassword.toCharArray());
        logger.finest("Logged in");
      } catch (Exception e) {
        logger.log(Level.SEVERE, "Can't log in", e);
        return new EventResult();
      }
    
      usrMgr = oimClient.getService(UserManager.class);    
      roleMgr = oimClient.getService(RoleManager.class);
      orgMgr = oimClient.getService(OrganizationManager.class);
    } else {
      usrMgr = 
        Platform.getServiceForEventHandlers(UserManager.class 
                                            , ContextManager.getContextKey()
                                            , ContextManager.getContextType().toString()
                                            , ContextManager.getContextSubType()
                                            , ContextManager.getAllValuesFromCurrentContext());
      roleMgr = 
        Platform.getServiceForEventHandlers(RoleManager.class 
                                          , ContextManager.getContextKey()
                                          , ContextManager.getContextType().toString()
                                          , ContextManager.getContextSubType()
                                          , ContextManager.getAllValuesFromCurrentContext());
         orgMgr = Platform.getServiceForEventHandlers(OrganizationManager.class
                                        , ContextManager.getContextKey()
                                        , ContextManager.getContextType().toString()
                                        , ContextManager.getContextSubType()
                                        , ContextManager.getAllValuesFromCurrentContext());
    }
    
    // get user's current GrpIDs and GrpName to modify them
    Set<String> setAttr = new HashSet<String>();
    
    // for each user in case it's a bulk assignment
    for (String strUserKey : lstUsrKeys) {
      setAttr.clear();
      setAttr.add("AWSMgtGrpName");
      setAttr.add("AWSMgmtGrpIDs");
      setAttr.add(UserManagerConstants.AttributeName.USER_ORGANIZATION.getId());

      User usr;
      String orgName = null;  
      try {
        usr = usrMgr.getDetails(strUserKey, setAttr, false);
      } catch (Exception e) {
        logger.log(Level.SEVERE, "Can't get details for a user with key: " +
                                 strUserKey, e);
        return new EventResult();
      }

    String orgKey = usr.getAttribute(UserManagerConstants.AttributeName.USER_ORGANIZATION.getId()).toString();
    logger.finest("orgKey == " + orgKey);
        try {
            Organization organization= orgMgr.getDetails(orgKey, null, false);
            orgName = organization.getAttribute(OrganizationManagerConstants.AttributeName.ORG_NAME.getId()).toString();
            logger.finest("orgName == " + orgName);     
            
        } catch (OrganizationManagerException e) {
            logger.log(Level.SEVERE, "Exception :", e);
        }
       
    if(!orgName.equalsIgnoreCase("AWS Internal")){
            logger.finest("orgName is not  AWS Internal");  
      // add new GrpID and GrpName to existing values
      // if they are set otherwise nulls
      String strGrpName = null, strGrpIDs = null;

      if (usr.getAttribute("AWSMgtGrpName") != null) {
        strGrpName = usr.getAttribute("AWSMgtGrpName").toString();
      }

      if (usr.getAttribute("AWSMgmtGrpIDs") != null) {
        strGrpIDs = usr.getAttribute("AWSMgmtGrpIDs").toString();
      }

      setAttr.clear();
      setAttr.add(RoleManagerConstants.RoleAttributeName.NAME.getId());
      setAttr.add(RoleManagerConstants.RoleAttributeName.DESCRIPTION.getId());

      // for each role get its description and name
      for (String strRoleKey : setRoleKeys) {
        Role role;
        try {
          role = roleMgr.getDetails(strRoleKey, setAttr);
        } catch (Exception e) {
          logger.log(Level.SEVERE, 
                     "Can't get role details for role key: " + strRoleKey, e);
          return new EventResult();
        }

        String strRoleName = role.getName();
        String strRoleDesc = role.getDescription();
        logger.finest("Role name: " + strRoleName + " Description: " + strRoleDesc);
 
        // do nothing for default delegated admin
				if (strRoleName.indexOf("aws_delegated_admin_default") == 0) {
          logger.finest("The role to assign is aws_delegated_admin_default one. Skipping...");
          break;
					
				// assign User Administrator to user for the Org with GrpId == RoleDesc
				// when a role assigned is aws_delegated_admin_xxx (xxx is GrpId)
        } else if (strRoleName.indexOf("aws_delegated_admin") == 0) {
					logger.finest("The role is aws_delegated_admin_xxx one. Need to assign admin role...");
					
					Pattern ptrn = Pattern.compile("aws_delegated_admin_([0-9]+)");
					Matcher match = ptrn.matcher(strRoleName);
					
					if (match.find()) {
						if (!assignUserAdminRole(strUserKey, match.group(1))) {
							logger.finest("Can't assign User Admin role to user:" + strUserKey);
						} else {
							logger.finest("User Admin role for user:" + strUserKey + " has been " +
								"successfully assigned for Organization with GrpId:" + match.group(1));
						}
					}
				  break;
				}
        
        if (strGrpName != null) {
          strGrpName += "," + strRoleName;
        } else {
          strGrpName = strRoleName;
        }
                
        if (strGrpIDs != null) {
          strGrpIDs += "," + strRoleDesc;
        } else {
          strGrpIDs = strRoleDesc;
        }
      }
      
      logger.finest("Setting new attribute values to: " + strGrpName + 
                    " and " + strGrpIDs);      
    
      usr = new User(strUserKey);
      usr.setAttribute("AWSMgtGrpName", strGrpName);
      usr.setAttribute("AWSMgmtGrpIDs", strGrpIDs);

      try {
        usrMgr.modify(usr);
      } catch (Exception e) {
        logger.log(Level.SEVERE, "Can't set new user attributes", e);
        return new EventResult();
      }
    }else{
        logger.finest("orgName is AWS Internal. So skipping UDF updation.");  
    }
    }
    logger.exiting(TAG, "execute");
    return new EventResult();
  }

	/**
	 * Method to assign admin role "User Administrator" for an organization 
	 * with a given Group ID
	 * @param strUserKey User key to assign the role to
	 * @param strOrgGrpId Organization Group Id
	 * @return true on success, false otherwise
	 */
	private boolean assignUserAdminRole(String strUserKey, String strOrgGrpId) {
		logger.entering(TAG, "assignUserAdminRole", strUserKey + " " + strOrgGrpId);
		
		boolean bRet = true;
		
		if (strUserKey == null || strOrgGrpId == null) {
			logger.warning("Params can't be null. Exiting...");
			return false;
		}
		
		OrganizationManager orgMgr = Platform.getService(OrganizationManager.class);
		AdminRoleService srvAdmRole = Platform.getService(AdminRoleService.class);
		
	  List<Organization> lstOrg = null;
	  AdminRole admRoleUserAdmin = null;

		try {
		  SearchCriteria critOrgDesc 
		    = new SearchCriteria("grp_id", strOrgGrpId, SearchCriteria.Operator.EQUAL);
			lstOrg = orgMgr.search(critOrgDesc, null, null);
			logger.finest("Organization key: " + lstOrg.get(0).getEntityId());
			
			admRoleUserAdmin = srvAdmRole.getAdminRole("OrclOIMUserAdmin");		
			logger.finest("Admin role display name: " + admRoleUserAdmin.getRoleDisplayName());
		} catch (OrganizationManagerException e) {
			logger.severe("Can't find organization with GrpId:" + strOrgGrpId);
			return false;
		}

		// check if Admin Role is alread assigned 
	  List<AdminRoleMembership> lstAdmRolesAssigned =
	    srvAdmRole.listUsersMembership(strUserKey, "OrclOIMUserAdmin", 
																		 lstOrg.get(0).getEntityId(), false, null);
	  logger.finest("List of assigned admin roles for user: " + lstAdmRolesAssigned.toString());

		if (lstAdmRolesAssigned.size() > 0) {
			logger.finest("User Administrator admin role is already assigned to the user" +
				" for this organization. Exiting...");
			return true;
		}
		
		// prepare membership
		AdminRoleMembership membership = new AdminRoleMembership();
		membership.setAdminRole(admRoleUserAdmin);
		membership.setUserId(strUserKey);
		membership.setScopeId(lstOrg.get(0).getEntityId());
		membership.setHierarchicalScope(false);
		
	  // assign admin role
		srvAdmRole.addAdminRoleMembership(membership);

		logger.exiting(TAG, "assignUserAdminRole", strUserKey + " " + strOrgGrpId + 
																							 " -> " + bRet);
		return bRet;
	}
	
  public BulkEventResult execute(long l, long l2,
                                 BulkOrchestration bulkOrchestration) {
    logger.entering(TAG, "bulk execute");
    return new BulkEventResult();
  }

  public void compensate(long l, long l2,
                         AbstractGenericOrchestration abstractGenericOrchestration) {
  }

  public boolean cancel(long l, long l2,
                        AbstractGenericOrchestration abstractGenericOrchestration) {
    return false;
  }

  public void initialize(HashMap<String, String> hashMap) {
  }
}
