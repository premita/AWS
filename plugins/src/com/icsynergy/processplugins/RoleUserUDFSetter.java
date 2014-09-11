package com.icsynergy.processplugins;

import java.io.Serializable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import oracle.iam.conf.api.SystemConfigurationService;
import oracle.iam.identity.rolemgmt.api.RoleManager;
import oracle.iam.identity.rolemgmt.api.RoleManagerConstants;
import oracle.iam.identity.rolemgmt.vo.Role;
import oracle.iam.identity.usermgmt.api.UserManager;
import oracle.iam.identity.usermgmt.vo.User;
import oracle.iam.platform.OIMClient;
import oracle.iam.platform.Platform;
import oracle.iam.platform.kernel.spi.PostProcessHandler;
import oracle.iam.platform.kernel.vo.AbstractGenericOrchestration;
import oracle.iam.platform.kernel.vo.BulkEventResult;
import oracle.iam.platform.kernel.vo.BulkOrchestration;
import oracle.iam.platform.kernel.vo.EventResult;
import oracle.iam.platform.kernel.vo.Orchestration;


public class RoleUserUDFSetter implements PostProcessHandler {
  private final static Logger logger = Logger.getLogger("com.icsynergy");
  private final static String TAG = RoleUserUDFSetter.class.getCanonicalName();
  
  private final static String USERNAMESYSPROP = "AWS.Username";
  private final static String PWDSYSPROP = "AWS.Password";
  private final static String OIMURL = "AWS.OIMURL";
  
  public EventResult execute(long l, long l2, Orchestration orchestration) {
    logger.entering(TAG, "execute");
    
    Map<String,Serializable> map = orchestration.getParameters();
    logger.finest("Orch params: " + map.toString());

    List<String> lstUsrKeys = (List<String>) map.get("userKeys");

    List<String> arstrRoleKeys = null;
    if (map.containsKey("roleKey")) {
      String strRoleKey = map.get("roleKey").toString();
      arstrRoleKeys = new ArrayList<String>();
      arstrRoleKeys.add(strRoleKey);
    } 
    else if (map.containsKey("roleKeys")) {
      arstrRoleKeys = (List<String>) map.get("roleKeys");
    }

    logger.finest("User Keys: " + lstUsrKeys.toString());
    logger.finest("Role keys: " + arstrRoleKeys.toString());
    
    OIMClient oimClient = null;  
    // log into the system    
    try {
      SystemConfigurationService cfgServ = 
        Platform.getService(SystemConfigurationService.class);

      String strOimUserName =
        cfgServ.getSystemPropertiesForUnauthenticatedUsers(USERNAMESYSPROP)
        .getPtyValue();

      String strOimPassword =
        cfgServ.getSystemPropertiesForUnauthenticatedUsers(PWDSYSPROP)
        .getPtyValue();

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
  
    UserManager usrMgr = oimClient.getService(UserManager.class);    
    RoleManager roleMgr = oimClient.getService(RoleManager.class);

    // get user's current GrpIDs and GrpName to modify them
    Set<String> setAttr = new HashSet<String>();
    
    // for each user in case it's a bulk assignment
    for (String strUserKey : lstUsrKeys) {
      setAttr.clear();
      setAttr.add("AWSMgtGrpName");
      setAttr.add("AWSMgmtGrpIDs");
      
      User usr;
      try {
        usr = usrMgr.getDetails(strUserKey, setAttr, false);
      } catch (Exception e) {
        logger.log(Level.SEVERE, "Can't get details for a user with key: " +
                                 strUserKey, e);
        return new EventResult();
      }

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
      for (String strRoleKey : arstrRoleKeys) {
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
 
        if (strRoleName.indexOf("aws_delegated_admin") == 0) {
          logger.finest("The role to assign is AWS_xxx one. Skipping");
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
    }
    
    logger.exiting(TAG, "execute");
    return new EventResult();
  }

  public BulkEventResult execute(long l, long l2,
                                 BulkOrchestration bulkOrchestration) {
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
