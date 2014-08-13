package com.icsynergy.processplugins;

import java.io.Serializable;

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
    String strRoleKey = map.get("roleKey").toString();

    logger.finest("Role Key: " + strRoleKey);
    logger.finest("User Keys: " + lstUsrKeys.toString());

    // get role name which is equal to org name 
    RoleManager roleMgr = Platform.getService(RoleManager.class);
    Set<String> setAttr = new HashSet<String>();
    setAttr.add(RoleManagerConstants.RoleAttributeName.NAME.getId());
    setAttr.add(RoleManagerConstants.RoleAttributeName.DESCRIPTION.getId());
    
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

    // get user's current GrpIDs and GrpName to modify them
    UserManager usrMgr = 
      Platform.getServiceForEventHandlers(UserManager.class,
                                          ContextManager.getContextKey().toString(),
                                          ContextManager.getContextType().toString(),
                                          ContextManager.getContextSubType().toString(),
                                          ContextManager.getAllValuesFromCurrentContext());
    setAttr.clear();
    setAttr.add("AWSMgtGrpName");
    setAttr.add("AWSMgmtGrpIDs");
    
    // for each user in case it's a bulk assignment
    for (String strUserKey : lstUsrKeys) {
      User usr;
      try {
        usr = usrMgr.getDetails(strUserKey, setAttr, false);
      } catch (Exception e) {
        logger.log(Level.SEVERE, "Can't get details for a user with key: " +
                                 strUserKey, e);
        return new EventResult();
      }
      
      // add new GrpID and GrpName to existing values
      // or just set them if they are null
      String strGrpName = null, strGrpIDs = null;
      
      if (usr.getAttribute("AWSMgtGrpName") != null) {
        strGrpName = usr.getAttribute("AWSMgtGrpName").toString();
        //split the string into an array
        String[] arstrGrpNames = strGrpName.split("[,:]");
        
        String[] arstrNewGrpNames = null;
        //search array for a string and remove it from it
        for (int i = 0; i < arstrGrpNames.length; i++) {
          if (arstrGrpNames[i].equalsIgnoreCase(strRoleName)) {
            arstrGrpNames[i] = arstrGrpNames[arstrGrpNames.length - 1];
            arstrNewGrpNames = Arrays.copyOf(arstrGrpNames, arstrGrpNames.length - 1);
            break;
          }
        }
        if (arstrNewGrpNames != null) {
          strGrpName = Arrays.toString(arstrNewGrpNames).replace(", ", ",")
            .replaceAll("[\\[\\]]", "");
        } else {
          logger.warning("GrpName " + strRoleName + " has not been found");
          strGrpName = Arrays.toString(arstrGrpNames).replace(", ", ",")
            .replaceAll("[\\[\\]]", "");          
        }
      } else {
        logger.warning("User AWSMgtGrpName attribute is empty");
      }
      
      if (usr.getAttribute("AWSMgmtGrpIDs") != null) {
        strGrpIDs = usr.getAttribute("AWSMgmtGrpIDs").toString();
        //split the string into an array
        String[] arstrGrpIDs = strGrpIDs.split("[,:]");
        
        String[] arstrNewGrpIDs = null;
        //search array for a string and remove it from it
        for (int i = 0; i < arstrGrpIDs.length; i++) {
          if (arstrGrpIDs[i].equalsIgnoreCase(strRoleDesc)) {
            arstrGrpIDs[i] = arstrGrpIDs[arstrGrpIDs.length - 1];
            arstrNewGrpIDs = Arrays.copyOf(arstrGrpIDs, arstrGrpIDs.length - 1);
            break;
          }
        }
        
        if (arstrNewGrpIDs != null) {
          strGrpIDs = Arrays.toString(arstrNewGrpIDs).replace(", ", ",")
            .replaceAll("[\\[\\]]", "");
        } else {
          logger.warning("GrpID " + strRoleDesc + " has not been found");
          strGrpIDs = Arrays.toString(arstrGrpIDs).replace(", ", ",")
            .replaceAll("[\\[\\]]", "");
        }
      } else {
        logger.warning("User AWSMgmtGrpIDs attribute is empty");
      }

      logger.finest("Setting new attribute values to: " + strGrpName + 
                    " and " + strGrpIDs);      
      usr = new User(strUserKey);
      usr.setAttribute("AWSMgtGrpName", strGrpName);
      usr.setAttribute("AWSMgmtGrpIDs", strGrpIDs);

      try {
        usrMgr.modify(usr);
      } catch (Exception e) {
        logger.log(Level.SEVERE, "Can't modify a user", e);
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
