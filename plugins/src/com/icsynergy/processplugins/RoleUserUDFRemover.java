package com.icsynergy.processplugins;

import java.io.Serializable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import oracle.iam.identity.rolemgmt.api.RoleManager;
import oracle.iam.identity.rolemgmt.api.RoleManagerConstants;
import oracle.iam.identity.rolemgmt.vo.Role;
import oracle.iam.identity.usermgmt.api.UserManager;
import oracle.iam.identity.usermgmt.vo.User;
import oracle.iam.platform.Platform;
import oracle.iam.platform.context.ContextManager;
import oracle.iam.platform.kernel.spi.PostProcessHandler;
import oracle.iam.platform.kernel.vo.AbstractGenericOrchestration;
import oracle.iam.platform.kernel.vo.BulkEventResult;
import oracle.iam.platform.kernel.vo.BulkOrchestration;
import oracle.iam.platform.kernel.vo.EventResult;
import oracle.iam.platform.kernel.vo.Orchestration;

public class RoleUserUDFRemover implements PostProcessHandler {
  private final static Logger logger = Logger.getLogger("com.icsynergy");
  private final static String TAG = RoleUserUDFSetter.class.getCanonicalName();

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

    logger.finest("Role Key: " + arstrRoleKeys.toString());
    logger.finest("User Keys: " + lstUsrKeys.toString());

    RoleManager roleMgr = Platform.getService(RoleManager.class);
    UserManager usrMgr = 
      Platform.getServiceForEventHandlers(UserManager.class,
                                          ContextManager.getContextKey().toString(),
                                          ContextManager.getContextType().toString(),
                                          ContextManager.getContextSubType().toString(),
                                          ContextManager.getAllValuesFromCurrentContext());
    
    Set<String> setAttr = new HashSet<String>();
    // for each user
    for (String strUserKey : lstUsrKeys) {
      // get user's current GrpIDs and GrpName to modify them  
      setAttr.clear();
      setAttr.add("AWSMgtGrpName");
      setAttr.add("AWSMgmtGrpIDs");
      
      User usr;
      try {
        usr = usrMgr.getDetails(strUserKey, setAttr, false);
      } catch (Exception e) {
        logger.log(Level.SEVERE, "Can't get details for a user with key: " +
                                 strUserKey, e);
        logger.warning("Skipping user with key: " + strUserKey);
        continue;
      }

      String strGrpName = null, strGrpIDs = null;
      String[] arstrGrpNames = new String[0], arstrGrpIDs = new String[0];
      
      if (usr.getAttribute("AWSMgtGrpName") != null) {
        //split the string into an array of GrpNames
        arstrGrpNames = 
          usr.getAttribute("AWSMgtGrpName").toString().split("[,:]");
      }

      if (usr.getAttribute("AWSMgmtGrpIDs") != null) {
        //split the string into an array of GrpIDs
        arstrGrpIDs = 
          usr.getAttribute("AWSMgmtGrpIDs").toString().split("[,:]");
      }
      
      // get role name which is equal to org name and org desc
      setAttr.clear();
      setAttr.add(RoleManagerConstants.RoleAttributeName.NAME.getId());
      setAttr.add(RoleManagerConstants.RoleAttributeName.DESCRIPTION.getId());
      
      // for each role
      for (String strRoleKey : arstrRoleKeys) {
        Role role;
        try {
          role = roleMgr.getDetails(strRoleKey, setAttr);
        } catch (Exception e) {
          logger.log(Level.SEVERE, 
                     "Can't get role details for role key: " + strRoleKey, e);
          logger.warning("Skipping role with key: " + strRoleKey);
          continue;
        }
  
        String strRoleName = role.getName();
        String strRoleDesc = role.getDescription();
        logger.finest("Role name: " + strRoleName + " Description: " + strRoleDesc);
        
        // skip aws_delegated_admin_xxx roles
        if (strRoleName.indexOf("aws_delegated_admin") == 0) {
          logger.finest("The role to remove is aws_delegated_admin_xxx one. Skipping");
          continue;
        }
        
        // clean GrpName from array of GrpNames
        if (arstrGrpNames.length > 0) {
          //search array for a string and remove it from it
          for (int i = 0; i < arstrGrpNames.length; i++) {
            if (arstrGrpNames[i].equalsIgnoreCase(strRoleName)) {
              arstrGrpNames[i] = arstrGrpNames[arstrGrpNames.length - 1];
              arstrGrpNames = Arrays.copyOf(arstrGrpNames, arstrGrpNames.length - 1);
              break;
            }
          }
        } else {
          logger.warning("User's AWSMgtGrpName attribute is already empty. " + 
                         "Can't delete GrpName: " + strRoleName);
        }

        // clean GrpID from array of GrpIDs        
        if (arstrGrpIDs.length > 0) {
          //search array for a string and remove it from it
          for (int i = 0; i < arstrGrpIDs.length; i++) {
            if (arstrGrpIDs[i].equalsIgnoreCase(strRoleDesc)) {
              arstrGrpIDs[i] = arstrGrpIDs[arstrGrpIDs.length - 1];
              arstrGrpIDs = Arrays.copyOf(arstrGrpIDs, arstrGrpIDs.length - 1);
              break;
            }
          }          
        } else {
          logger.warning("User's AWSMgmtGrpIDs attribute is already empty. " +
                         "Can't delete GrpID:" + strRoleDesc);
        }  
      }
      
      if (arstrGrpNames.length > 0) {
        strGrpName = Arrays.toString(arstrGrpNames).replace(", ", ",")
          .replaceAll("[\\[\\]]", "");
      } else {
        logger.warning("New GrpName is empty");
        strGrpName = null;          
      }

      if (arstrGrpIDs.length > 0) {
        strGrpIDs = Arrays.toString(arstrGrpIDs).replace(", ", ",")
          .replaceAll("[\\[\\]]", "");
      } else {
        logger.warning("New GrpID is empty");
        strGrpIDs = null;
      }

      logger.finest("Setting new attribute values to: " + strGrpName + 
                    " and " + strGrpIDs);      
      usr = new User(strUserKey);
      usr.setAttribute("AWSMgtGrpName", strGrpName);
      usr.setAttribute("AWSMgmtGrpIDs", strGrpIDs);

      try {
        usrMgr.modify(usr);
        logger.finest("User's UDF have been successfully set");
      } catch (Exception e) {
        logger.log(Level.SEVERE, "Can't modify a user", e);
        logger.warning("Skipping user with key: " + strUserKey);
        continue;
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
