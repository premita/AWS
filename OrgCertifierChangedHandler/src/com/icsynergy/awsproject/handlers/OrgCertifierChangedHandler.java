package com.icsynergy.awsproject.handlers;

import oracle.iam.identity.exception.NoSuchUserException;
import oracle.iam.identity.exception.UserModifyException;
import oracle.iam.identity.exception.UserSearchException;
import oracle.iam.identity.exception.ValidationFailedException;
import oracle.iam.identity.orgmgmt.api.OrganizationManagerConstants;
import oracle.iam.identity.orgmgmt.vo.Organization;
import oracle.iam.identity.usermgmt.api.UserManager;
import oracle.iam.identity.usermgmt.api.UserManagerConstants;
import oracle.iam.identity.usermgmt.vo.User;
import oracle.iam.platform.Platform;
import oracle.iam.platform.entitymgr.vo.SearchCriteria;
import oracle.iam.platform.kernel.EventFailedException;
import oracle.iam.platform.kernel.spi.PostProcessHandler;
import oracle.iam.platform.kernel.vo.*;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class OrgCertifierChangedHandler implements PostProcessHandler {

    @Override
    public EventResult execute(long l, long l1, Orchestration orchestration) {
        final Logger logger = Logger.getLogger("com.icsynergy");
        logger.entering(this.getClass().getCanonicalName(), "execute", orchestration);

        EventResult res = new EventResult();

        if (orchestration.getParameters().containsKey(
                OrganizationManagerConstants.AttributeName.ORG_CERTIFIER_USER_KEY.getId())) {

            // get old certifier login
            Organization curOrg = (Organization) orchestration.getInterEventData().get("CURRENT_ORG");
            Long oldCertKey =
                    Long.valueOf(
                            String.valueOf(
                                    curOrg.getAttribute(
                                            OrganizationManagerConstants.AttributeName.ORG_CERTIFIER_USER_KEY.getId())));

            res = ChangeOrgUsersManager(
                    orchestration.getTarget().getEntityId(),
                    oldCertKey,
                    String.valueOf(orchestration.getParameters().get(
                                    OrganizationManagerConstants.AttributeName.ORG_CERTIFIER_USER_KEY.getId())
                    )
            );
        }

        logger.exiting(getClass().getCanonicalName(), "execute", res);
        return res;
    }

    /**
     * Changes a manager for all user within the given organization
     * @param orgKey Organization key
     * @param oldCertifierKey old certifier key
     * @param certifierKey new certifier key
     * @return EventResult
     * @throws EventFailedException
     */
    private EventResult ChangeOrgUsersManager(String orgKey, Long oldCertifierKey, String certifierKey) throws EventFailedException {
        Logger log = Logger.getLogger("com.icsynergy");
        log.entering(
                this.getClass().getCanonicalName(), "ChangeOrgUsersManager",
                new StringBuilder("orgKey=").append(orgKey).append(" certKey=").append(certifierKey)
        );

        log.finest("getting user manager service...");
        UserManager usrMgr =
                Platform.getServiceForEventHandlers(UserManager.class, "Org Certifier Change: " + orgKey, "ADMIN", null, null);

        // search for all active users in the org who have old certifier as a manager
        log.finest("setting criterias for a user search...");
        SearchCriteria critActive =
                new SearchCriteria(
                        UserManagerConstants.AttributeName.STATUS.getId(),
                        UserManagerConstants.AttributeValues.USER_STATUS_ACTIVE.getId(),
                        SearchCriteria.Operator.EQUAL);
        SearchCriteria critOrg =
                new SearchCriteria(
                        UserManagerConstants.AttributeName.USER_ORGANIZATION.getId(),
                        orgKey,
                        SearchCriteria.Operator.EQUAL
                );
        SearchCriteria critOldMgr =
                new SearchCriteria(
                        UserManagerConstants.AttributeName.MANAGER_KEY.getName(),
                        oldCertifierKey,
                        SearchCriteria.Operator.EQUAL);

        SearchCriteria critActiveInOrg = new SearchCriteria(critActive, critOrg, SearchCriteria.Operator.AND);
        SearchCriteria crit = new SearchCriteria(critActiveInOrg, critOldMgr, SearchCriteria.Operator.AND);

        List<User> lst;
        log.finest("searching...");
        try {
            lst = usrMgr.search(
                    crit,
                    new HashSet<>(Collections.singleton(UserManagerConstants.AttributeName.USER_KEY.getId())),
                    null);
            log.finest("List of active users in given org with an old manager: " + lst);
        } catch (UserSearchException e) {
            log.log(Level.SEVERE, "User search exception", e);
            throw new EventFailedException(e.getErrorCode(), e.getErrorData(), e.getCause());
        }

        ArrayList<String> listIds = new ArrayList<>();
        for (User usr : lst) {
            listIds.add(usr.getEntityId());
        }
        log.finest("List of user IDs: " + listIds);

        HashMap<String, Object> map =
                new HashMap<String, Object>(
                        Collections.singletonMap(
                                UserManagerConstants.AttributeName.MANAGER_KEY.getName(),
                                Long.valueOf(certifierKey)));

        log.finest("modifying users...");
        try {
            usrMgr.modify(listIds, map, false);
        } catch (NoSuchUserException | UserModifyException | ValidationFailedException e) {
            log.log(Level.SEVERE, "Exception modifying users", e);
            throw new EventFailedException(e.getMessage(), e.getErrorData(), e.getCause());
        }

        log.exiting(getClass().getCanonicalName(), "ChangeOrgUsersManager");
        return new EventResult();
    }

    @Override
    public BulkEventResult execute(long l, long l1, BulkOrchestration bulkOrchestration) {
        Logger log = Logger.getLogger("com.icsynergy");

        log.entering(getClass().getCanonicalName(), "bulk execute");
        return null;
    }

    @Override
    public void compensate(long l, long l1, AbstractGenericOrchestration abstractGenericOrchestration) {

    }

    @Override
    public boolean cancel(long l, long l1, AbstractGenericOrchestration abstractGenericOrchestration) {
        return false;
    }

    @Override
    public void initialize(HashMap<String, String> hashMap) {
    }
}
