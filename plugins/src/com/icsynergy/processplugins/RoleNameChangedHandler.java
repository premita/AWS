package com.icsynergy.processplugins;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import oracle.iam.identity.exception.NoSuchUserException;
import oracle.iam.identity.exception.RoleMemberException;
import oracle.iam.identity.exception.UserModifyException;
import oracle.iam.identity.exception.ValidationFailedException;
import oracle.iam.identity.rolemgmt.api.RoleManager;
import oracle.iam.identity.rolemgmt.api.RoleManagerConstants;
import oracle.iam.identity.rolemgmt.vo.Role;
import oracle.iam.identity.usermgmt.api.UserManager;
import oracle.iam.identity.usermgmt.vo.User;
import oracle.iam.platform.Platform;
import oracle.iam.platform.context.ContextManager;
import oracle.iam.platform.kernel.EventFailedException;
import oracle.iam.platform.kernel.spi.ConditionalEventHandler;
import oracle.iam.platform.kernel.spi.PostProcessHandler;
import oracle.iam.platform.kernel.vo.AbstractGenericOrchestration;
import oracle.iam.platform.kernel.vo.BulkEventResult;
import oracle.iam.platform.kernel.vo.BulkOrchestration;
import oracle.iam.platform.kernel.vo.EventResult;
import oracle.iam.platform.kernel.vo.Orchestration;


public class RoleNameChangedHandler implements ConditionalEventHandler,
                                               PostProcessHandler {
  public boolean isApplicable(AbstractGenericOrchestration abstractGenericOrchestration) {
    Logger
      .getLogger("com.icsynergy")
      .entering(this.getClass().getCanonicalName(), 
                "isApplicable",
                abstractGenericOrchestration.getParameters().toString());
      
    return (abstractGenericOrchestration.getParameters() 
            .get(RoleManagerConstants.RoleAttributeName.NAME.getId()) != null)
      && (!abstractGenericOrchestration
           .getParameters() 
           .get(RoleManagerConstants
                .RoleAttributeName.NAME.getId())
           .toString()
           .startsWith("aws_delegated_admin"));
           
  }

  public EventResult execute(long l, long l2, Orchestration orchestration) {
    final Logger logger = Logger.getLogger("com.icsynergy");
    final String TAG = this.getClass().getCanonicalName();
    final String UDFGRPSNAME = "AWSMgtGrpName";
    
    logger.entering(TAG, "execute", orchestration.getParameters());

    Role oldRole = 
      (Role) orchestration
      .getInterEventData()
      .get(orchestration.getTarget().getEntityId());
        
    // Role old and new names
    String strOldName = 
      oldRole
      .getAttribute(RoleManagerConstants
                    .RoleAttributeName.NAME.getId())
      .toString();
    String strNewName = 
      orchestration.getParameters()
      .get(RoleManagerConstants.RoleAttributeName.NAME.getId())
      .toString();
    logger.finest(String.format("Old role name: %s new role name: %s", 
                                strOldName, strNewName));
    
    RoleManager roleMgr = Platform.getService(RoleManager.class);
    UserManager usrMgr = 
      Platform.getServiceForEventHandlers(UserManager.class, 
                                          ContextManager.getContextKey(),
                                          ContextManager.getContextType().toString(), 
                                          ContextManager.getContextSubType(),
                                          ContextManager.getAllValuesFromCurrentContext());

    // get a list of users with this role
    logger.finest("Searching for users with this role");
    List<User> lstUsers;
    try {
      lstUsers = roleMgr.getRoleMembers(orchestration.getTarget().getEntityId(), true);
    } catch (RoleMemberException e) {
      logger.log(Level.SEVERE, "Exception searching for users with a role_key: " 
                               + oldRole.getEntityId());
      throw new EventFailedException("AWS-00001", 
                                     "Exception searching for users with a role_key: " 
                                     + oldRole.getEntityId(), 
                                     "",
                                     this.getClass().getName(), 
                                     e);
    }
    
    // cycle through all the users and replace Old Group Name with New Group Name
    logger.finest("The list of users to change: " + lstUsers);
    for (User usr : lstUsers) {
      String str = 
        (usr.getAttribute(UDFGRPSNAME) == null ? 
        "" : 
        usr.getAttribute(UDFGRPSNAME).toString());
      
      Set<String> setIds = new HashSet<>();
      if (!str.isEmpty()) {
        setIds = new HashSet<>(Arrays.asList(str.split(",")));
      }
      
      setIds.remove(strOldName);
      setIds.add(strNewName);
      
      // join string back 
      str = 
        setIds.toString().replaceAll(", ", ",").replaceAll("[\\[\\]]", "");
      logger.finest("Setting user's UDF to: " + str);
      
      // set user's attribute
      User usrToBe = new User(usr.getEntityId());
      usrToBe.setAttribute(UDFGRPSNAME, str);
      try {
        usrMgr.modify(usrToBe);
        logger.fine("Attribute " + UDFGRPSNAME + 
                      " for user key: " + usr.getEntityId() +
                      " successfully set to: " + str);
      } catch (NoSuchUserException 
               | UserModifyException 
               | ValidationFailedException e) {
        logger.log(Level.WARNING, "Exception setting " + UDFGRPSNAME 
                                  +" attribute to: " + str, e);
      }
    }

    logger.exiting(TAG, "execute");
    return new EventResult();
  }

  public BulkEventResult execute(long l, long l2,
                                 BulkOrchestration bulkOrchestration) {
    return null;
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
