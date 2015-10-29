package com.icsynergy;

import java.io.PrintWriter;
import java.io.StringWriter;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.logging.Logger;

import oracle.iam.identity.usermgmt.api.UserManager;
import oracle.iam.identity.usermgmt.api.UserManagerConstants;
import oracle.iam.identity.usermgmt.vo.User;
import oracle.iam.identity.usermgmt.vo.UserManagerResult;
import oracle.iam.platform.Platform;
import oracle.iam.platform.entitymgr.vo.SearchCriteria;
import oracle.iam.scheduler.vo.TaskSupport;

public class expirePasswordDisable extends TaskSupport {
    private static final String TAG = "RevokeAccontTask";
    private static final Logger m_logger = Logger.getLogger("com.icsynergy");
    
    public expirePasswordDisable() {
        super();
    }

    public void execute(HashMap hashMap) throws Exception {
        m_logger.entering( TAG, "execute", hashMap.toString() );
        
        int iPeriod = 0;
        
        // check required parameters
        if(!hashMap.containsKey("Period")) {
            m_logger.warning("Requred parameters are missing");
            m_logger.exiting( TAG, "execute" );
            return;
        } else {
            iPeriod = Integer.parseInt( hashMap.get("Period").toString() );
        }

        
        // get date from $iPeriod days ago
        Calendar cal = new GregorianCalendar();
        cal.setTime(new Date());
        cal.add(Calendar.DAY_OF_MONTH, (-1*iPeriod));
        Date periodDays = cal.getTime();
        
        // searching for users with password expiration date at least $iPeriod days ago
        SearchCriteria critPassexpired = new SearchCriteria(UserManagerConstants.AttributeName.PWD_EXPIRE_DATE.getName(), periodDays, SearchCriteria.Operator.LESS_EQUAL);
        
        // searching for active users
        SearchCriteria critActive = new SearchCriteria(UserManagerConstants.AttributeName.STATUS.getName(), "ACTIVE", SearchCriteria.Operator.EQUAL);
        
        // final criteria criteria
        SearchCriteria criteria = new SearchCriteria( critPassexpired, critActive, SearchCriteria.Operator.AND);
        
        // Hashset for holding ID
        Set<String> retAttr = new HashSet<String>();
        retAttr.add( UserManagerConstants.AttributeName.USER_LOGIN.getName() );
        
        // get interface
        UserManager usrmgr = Platform.getService( UserManager.class );
        
        // get user list who meet criteria
        List<User> list = usrmgr.search(criteria, retAttr, null);
        
        // If no users to process; exit
        if(list.isEmpty()){
            m_logger.finest("No users matching search criteria");
            m_logger.exiting( TAG, "execute" );
            return;
        }
        
        // Put userIDs into list for bulk operation
        ArrayList<String> usrIDs = new ArrayList<String>();
        
        for(User usr : list){
            m_logger.finest("User: "+usr.getId()+" "+usr.getLogin());
            usrIDs.add(usr.getId());
        }
        
        // Bulk disable operation
        UserManagerResult usrmgrResult = usrmgr.disable(usrIDs, false);
        
        // Log operation results
        String bulkDisableStatus = usrmgrResult.getStatus();
        List bulkDisableSucceed = usrmgrResult.getSucceededResults();
        HashMap<String, String> bulkDisableFailed = usrmgrResult.getFailedResults();
        
        m_logger.finest("Status: "+bulkDisableStatus);
        if(!bulkDisableSucceed.isEmpty()){
            m_logger.finest("Succeeded");
            for(Object o : bulkDisableSucceed)
                m_logger.finest(o.toString());
        }
        
        if(!bulkDisableFailed.entrySet().isEmpty()){
            m_logger.finest("Failed");
            for(Map.Entry<String, String> entry : bulkDisableFailed.entrySet())
                m_logger.finest(entry.getKey()+": "+entry.getValue());
        }            
        
        m_logger.exiting( TAG, "execute" );
    }

    public HashMap getAttributes() {
        return new HashMap();
    }

    public void setAttributes() {
    }
}
