package com.icsynergy.awsproject;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;

import java.util.List;
import java.util.logging.Logger;

import oracle.iam.identity.usermgmt.api.UserManager;
import oracle.iam.identity.usermgmt.api.UserManagerConstants;
import oracle.iam.identity.usermgmt.vo.User;
import oracle.iam.notification.api.NotificationService;
import oracle.iam.notification.vo.NotificationEvent;
import oracle.iam.platform.Platform;
import oracle.iam.platform.entitymgr.vo.SearchCriteria;
import oracle.iam.platform.entitymgr.vo.SearchRule;
import oracle.iam.scheduler.vo.TaskSupport;

public class PasswordWarningNotificator extends TaskSupport {
  public PasswordWarningNotificator() {
    super();
  }

  public void execute(HashMap mapParams) throws Exception {
    Logger logger = Logger.getLogger("com.icsynergy");
    String TAG = this.getClass().getCanonicalName();
    
    logger.entering(TAG, "execute", mapParams);
    
    //check required params
    if (mapParams.get("Days Before Expiration") == null ||
        Integer.parseInt(mapParams.get("Days Before Expiration").toString()) < 1) {
      String str = "Invalid parameter value for 'Days before Expiration'";
      logger.severe(str);
      throw new Exception(str);
    }

    if (mapParams.get("Template Name") == null 
        || mapParams.get("Template Name").toString().isEmpty()) {
      String str = "Parameter 'Template Name' is empty or not set";
      logger.severe(str);
      throw new Exception(str);
    }
    
    logger.finest("Getting User Manager...");
    UserManager usrMgr = Platform.getService(UserManager.class);

    logger.finest("Getting Notification Service...");
    NotificationService notifSrv = 
      Platform.getService(NotificationService.class);
    
    logger.finest("Creating a notification event and " +
      "setting its constant attributes...");
    NotificationEvent event = new NotificationEvent();
    event.setSender(null);
    event.setTemplateName(mapParams.get("Template Name").toString());
    
    logger.finest("Setting criterias...");
    SearchCriteria critStatusActive =
      new SearchCriteria(UserManagerConstants.AttributeName.STATUS.getId(), 
                         UserManagerConstants.AttributeValues.USER_STATUS_ACTIVE.getId(),
                         SearchCriteria.Operator.EQUAL);
    SearchCriteria critExpiredZero =
      new SearchCriteria(UserManagerConstants.AttributeName.PWD_EXPIRED.getId(),
                         "0", SearchCriteria.Operator.EQUAL);
    SearchCriteria critExpiredNull =
      new SearchCriteria(UserManagerConstants.AttributeName.PWD_EXPIRED.getId(),
                         null, SearchCriteria.Operator.EQUAL);
    
    // password expiration data <= today + paramDays
    GregorianCalendar cal = new GregorianCalendar();
    cal.setTime(new Date());
    cal.set(Calendar.HOUR, 0);
    cal.set(Calendar.MINUTE, 0);
    cal.set(Calendar.SECOND, 0);
    cal.add(Calendar.DATE, 
            Integer.parseInt(mapParams
                             .get("Days Before Expiration").toString()));
    Date dtTodayPlus = cal.getTime();
    logger.finest("Date to compare: " + dtTodayPlus.toString());
    
    SearchCriteria critDaysBeforeExpiration =
      new SearchCriteria(UserManagerConstants
                         .AttributeName.PWD_EXPIRE_DATE.getId(),
                         dtTodayPlus, SearchRule.Operator.EQUAL);
    
    SearchCriteria critExpired = 
      new SearchCriteria(critExpiredNull, critExpiredZero, 
                         SearchCriteria.Operator.OR);
    SearchCriteria critExpiredAndStatus =
      new SearchCriteria(critExpired, critStatusActive, 
                         SearchCriteria.Operator.AND);
    SearchCriteria critDaysAndExpiredAndStatus = 
      new SearchCriteria(critDaysBeforeExpiration, critExpiredAndStatus,
                         SearchCriteria.Operator.AND);
    
    logger.finest("Searching for users based on criterias...");
    List<User> lstUsers = 
      usrMgr.search(critDaysAndExpiredAndStatus, null, null);
    logger.finest("Total " + lstUsers.size() + " user(s) to notify");
        
    HashMap<String,Object> mapEventParams = new HashMap<>();
    
    for (User usr : lstUsers) {
      logger.finest("userLoginId: " + usr.getLogin());
      mapEventParams.put("userLoginId", usr.getLogin());
      event.setParams(mapEventParams);
      
      String[] arLogins = { usr.getLogin() };
      event.setUserIds(arLogins);
      
      logger.finest("Sending notification...");
      logger.finest(String.format("User : %s has %s been notified",
                                  usr.getLogin(), 
                                  notifSrv.notify(event) ? "" : "not"));
      
      if (this.isStop()) {
        logger.finest("Task was interrupted, exiting...");
        break;
      }
    }
    
    logger.exiting(TAG, "execute");
  }

  public HashMap getAttributes() {
    return null;
  }

  public void setAttributes() {
  }
}
