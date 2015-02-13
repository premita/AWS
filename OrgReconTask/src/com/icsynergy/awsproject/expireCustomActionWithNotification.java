package com.icsynergy.awsproject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import java.util.logging.Logger;

import oracle.iam.identity.usermgmt.api.UserManager;
import oracle.iam.identity.usermgmt.api.UserManagerConstants;
import oracle.iam.identity.usermgmt.vo.User;
import oracle.iam.identity.usermgmt.vo.UserManagerResult;
import oracle.iam.notification.api.NotificationService;
import oracle.iam.notification.vo.NotificationEvent;
import oracle.iam.platform.Platform;
import oracle.iam.platform.entitymgr.vo.SearchCriteria;
import oracle.iam.scheduler.vo.TaskSupport;

public class expireCustomActionWithNotification extends TaskSupport {
    public expireCustomActionWithNotification() {
        super();
    }

    public void execute(HashMap hashMap) throws Exception {
      String TAG = this.getClass().getCanonicalName();
      Logger logger = Logger.getLogger("com.icsynergy");
      
      logger.entering(TAG, "execute", hashMap.toString());
      
      // check required parameters
      if (!hashMap.containsKey("Days Past Expiration")) {
        throw new Exception("A required parameter missing: Days Past Expiration");
      }
      
      // we allow empty notification template -> no notification then
      NotificationService notifSrv = 
        Platform.getService(NotificationService.class);
      String strTemplateName = "";
      if (!hashMap.containsKey("Template Name")) {
        throw new Exception("A required parameter missing: Template Name");
      } else if (!hashMap.get("Template Name").toString().isEmpty()) {
        strTemplateName = hashMap.get("Template Name").toString();
        // try to lookup template early to throw an exception if template's missing
        notifSrv.lookupTemplate(strTemplateName, Locale.getDefault());
      }
      
      SearchCriteria critStatus = null;
      String strAction = "";
      if (!hashMap.containsKey("Action")) {
        throw new Exception("A required parameter missing: Action");
      } else if (!hashMap.get("Action").toString().isEmpty()) {
        strAction = hashMap.get("Action").toString();
        switch (strAction.toUpperCase()) {
        case "LOCK" :
        case "DISABLE" :
          // search for active only
          critStatus =
            new SearchCriteria(UserManagerConstants
                               .AttributeName.STATUS.getName(), 
                               UserManagerConstants
                               .AttributeValues.USER_STATUS_ACTIVE.getId(), 
                               SearchCriteria.Operator.EQUAL);
          break;
        case "DELETE" : 
          // search for disabled only
          critStatus =
            new SearchCriteria(UserManagerConstants
                               .AttributeName.STATUS.getName(), 
                               UserManagerConstants
                               .AttributeValues.USER_STATUS_DISABLED.getId(), 
                               SearchCriteria.Operator.EQUAL);            
          break;
        default :
          throw new Exception("Action " + strAction 
                              + " is unknown. Should be [Lock, Disable, Delete]");
      }
      
      if (strAction.isEmpty() && strTemplateName.isEmpty()) {
        logger.warning("Action and Template Name are empty. " +
          "Nothing to do, exiting...");
        return;
      }
      
      int iPeriod = 
        Integer.parseInt(hashMap.get("Days Past Expiration").toString());
      
      // get date from $iPeriod days ago
      Calendar cal = new GregorianCalendar();
      cal.setTime(new Date());
      cal.add(Calendar.DATE, -iPeriod);
      Date periodDays = cal.getTime();
      
      // searching for users with password expiration date at least $iPeriod days ago
      SearchCriteria critPassexpired 
        = new SearchCriteria(UserManagerConstants.AttributeName
                             .PWD_EXPIRE_DATE.getName(), 
                             periodDays, SearchCriteria.Operator.EQUAL);
            
      // final criteria criteria
      SearchCriteria criteria = 
        new SearchCriteria(critPassexpired, critStatus, SearchCriteria.Operator.AND);
      
      // Hashset for holding usr_login
      Set<String> retAttr = new HashSet<>();
      retAttr.add( UserManagerConstants.AttributeName.USER_LOGIN.getName() );
      
      // get interface
      logger.finest("Getting User Manager interface...");
      UserManager usrmgr = Platform.getService(UserManager.class);

      logger.finest("Searching for users...");
      // get user list who meet criteria
      List<User> list = usrmgr.search(criteria, retAttr, null);
      
      // If no users to process; exit
      if (list.isEmpty()) {
          logger.finest("No users matching search criteria");
          logger.exiting(TAG, "execute");
          return;
      }
      
      NotificationEvent event = new NotificationEvent();
      // if notification template set
      if (!strTemplateName.isEmpty()) {
        event.setTemplateName(strTemplateName.toString());
        event.setSender(null);
      }
      
      // Put userIDs into list for bulk operation
      ArrayList<String> usrIDs = new ArrayList<>();
      
      for(User usr : list){
        // stop if interrupted
        if (isStop()) {
          logger.warning("Job's has been interrupted, exiting...");
          return;
        }
        
        logger.finest("usr_key: " + usr.getId() 
                      + " usr_login: " +usr.getLogin());
        usrIDs.add(usr.getId());
        
        // if notification template set
        if (!strTemplateName.isEmpty()) {
          String[] arToId = { usr.getLogin() };
          event.setUserIds(arToId);
          
          HashMap<String, Object> params = new HashMap<>();
          params.put("usr_key", usr.getEntityId());
          params.put("userLoginId", usr.getLogin());
          event.setParams(params);
          
          logger.finest("Sending notification... " 
                        + (notifSrv.notify(event) ? "success" : "failure"));
        }
      }          
      
      // if action is set
      if (!strAction.isEmpty()) {
        // Bulk action
        UserManagerResult usrmgrResult = null;
      
        logger.finest("Performing Action=" + strAction + " for users found...");
        switch (strAction.toUpperCase()) {
        case "LOCK" :
          usrmgrResult = usrmgr.lock(usrIDs, false, false);
          break;
        case "DISABLE" :
          usrmgrResult = usrmgr.disable(usrIDs, false);
          break;
        case "DELETE" :
          usrmgrResult = usrmgr.delete(usrIDs, false);
          break;
        }

        // Log operation results
        logger.finest("Status: " + usrmgrResult.getStatus());

        List lstActionSucceed = usrmgrResult.getSucceededResults();
        HashMap<String, String> mapDisableFailed = 
          usrmgrResult.getFailedResults();
        
        if (!lstActionSucceed.isEmpty()) {
            logger.finest("Succeeded" + lstActionSucceed.toString());
        }
        
        if(mapDisableFailed.size() > 0){
            logger.finest("Failed" + mapDisableFailed.toString());
        }
      }
      
    }
    logger.exiting(TAG, "execute");
  }

    public HashMap getAttributes() {
        return new HashMap();
    }

    public void setAttributes() {
    }
}
