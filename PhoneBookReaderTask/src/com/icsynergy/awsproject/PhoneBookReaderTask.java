package com.icsynergy.awsproject;

import commonj.work.WorkManager;
import oracle.iam.identity.orgmgmt.api.OrganizationManager;
import oracle.iam.identity.orgmgmt.vo.Organization;
import oracle.iam.identity.usermgmt.api.UserManager;
import oracle.iam.identity.usermgmt.api.UserManagerConstants;
import oracle.iam.identity.usermgmt.vo.User;
import oracle.iam.platform.Platform;
import oracle.iam.platform.entitymgr.vo.SearchCriteria;
import oracle.iam.scheduler.vo.TaskSupport;
import weblogic.management.runtime.ExecuteThread;

import javax.naming.InitialContext;
import java.util.HashMap;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Logger;

public class PhoneBookReaderTask extends TaskSupport{
    @Override
    public void execute(HashMap hashMap) throws Exception {
        final String strParamOrgName = "Organization Name";
        final String strOrgNameEmpty = "Organization name is empty";

        Logger log = Logger.getLogger("com.icsynergy");
        log.entering(this.getClass().getCanonicalName() + " execute", hashMap.toString());

        log.finest("getting organization name parameter");
        String strOrgName = String.valueOf(hashMap.get(strParamOrgName));
        if(strOrgName.isEmpty())
            throw new Exception(strOrgNameEmpty);
        log.finer("organization: " + strOrgName);

        OrganizationManager orgMgr = Platform.getService(OrganizationManager.class);
        UserManager usrMgr = Platform.getService(UserManager.class);

        log.finest("looking up organization details");
        Organization org = orgMgr.getDetails(strOrgName, null, true);

        log.finest("preparing search criterias");
        SearchCriteria critActive =
                new SearchCriteria(
                        UserManagerConstants.AttributeName.STATUS.getId(),
                        UserManagerConstants.AttributeValues.USER_STATUS_ACTIVE.getId(),
                        SearchCriteria.Operator.EQUAL
                );
        SearchCriteria critOrg =
                new SearchCriteria(
                        UserManagerConstants.AttributeName.USER_ORGANIZATION.getId(),
                        org.getEntityId(), SearchCriteria.Operator.EQUAL
                );
        SearchCriteria crit = new SearchCriteria(critActive, critOrg, SearchCriteria.Operator.AND);

        log.finest("searching for active users in the given organization");
        List<User> lstUsr = usrMgr.search(crit, null, null);

        if (!lstUsr.isEmpty()) {
            log.finer("user list size: " + lstUsr.size());

            log.finest("obtaining initial context");
            InitialContext ic = new InitialContext();
            WorkManager wm = (WorkManager) ic.lookup("java:comp/env/wm/default");

            log.finest("creating fork/join pool and submitting a task");
            ForkJoinPool pool = new ForkJoinPool();
            pool.execute(new PhoneBookManagerRetriever(lstUsr));
        } else {
            log.fine("no active users found in the organization");
        }

        log.exiting(this.getClass().getCanonicalName() + " execute", null);
    }

    @Override
    public HashMap getAttributes() {
        return null;
    }

    @Override
    public void setAttributes() {

    }
}
