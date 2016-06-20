package com.icsynergy;

import java.util.ArrayList;
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

public class DisableOIMUser extends TaskSupport {
    private static final String TAG = "DisableOIMUser";
    private static final Logger m_logger = Logger.getLogger("com.icsynergy");
    
    public DisableOIMUser() {
        super();
    }

    public void execute(HashMap hashMap) throws Exception {
        m_logger.entering( TAG, "execute", hashMap.toString() );
        
        String OrgName = null;
        
        // check required parameters
        if(!hashMap.containsKey("Organization Name")) {
            m_logger.warning("Requred parameters are missing");
            m_logger.exiting( TAG, "execute" );
            return;
        } else {
            OrgName = hashMap.get("Organization Name").toString();
            m_logger.finest("Given Org Name: "+OrgName);
        }
        
        // searching for users with given organization    
        SearchCriteria OrgSearchCriteria = new SearchCriteria("Organization Name", OrgName, SearchCriteria.Operator.EQUAL);
        
        // searching for active users
        SearchCriteria critActive = new SearchCriteria(UserManagerConstants.AttributeName.STATUS.getName(), 
                                    UserManagerConstants.AttributeValues.USER_STATUS_ACTIVE.getId(), SearchCriteria.Operator.EQUAL);
        
        // final criteria criteria
        SearchCriteria criteria = new SearchCriteria( OrgSearchCriteria, critActive, SearchCriteria.Operator.AND);
        
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
        
        List bulkDisableSucceed = usrmgrResult.getSucceededResults();
        HashMap<String, String> bulkDisableFailed = usrmgrResult.getFailedResults();

        if(!bulkDisableSucceed.isEmpty()){
            m_logger.finest("Succeeded");
            for(Object o : bulkDisableSucceed)
                m_logger.finest("User Key for the Disabled User : [" + o.toString()+"]");
        }
        
        if(!bulkDisableFailed.entrySet().isEmpty()){
            m_logger.finest("Failed");
            for(Map.Entry<String, String> entry : bulkDisableFailed.entrySet())
                m_logger.finest("User Key for not Disabled User : ["+entry.getKey()+"] Reason of Failure : ["+entry.getValue()+"]");
        }          
        
        m_logger.exiting( TAG, "execute" );
    }

    public HashMap getAttributes() {
        return new HashMap();
    }

    public void setAttributes() {
    }
}
