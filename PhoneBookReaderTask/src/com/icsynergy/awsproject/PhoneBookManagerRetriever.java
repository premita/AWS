package com.icsynergy.awsproject;

import oracle.iam.identity.usermgmt.vo.User;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.RecursiveAction;

public class PhoneBookManagerRetriever extends RecursiveAction {
    private List<User> lst;

    public PhoneBookManagerRetriever(List<User> list) {
       lst = list;
    }

    @Override
    protected void compute() {
        if(lst.size() > 1) {
            try {
                invokeAll(
                        Arrays.asList(
                            new PhoneBookManagerRetriever(lst.subList(0, lst.size()/2)),
                            new PhoneBookManagerRetriever(lst.subList(lst.size()/2, lst.size()))
                        )
                );
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            System.out.println(Thread.currentThread().getName() + ": " + lst.get(0).getLogin());
        }
    }


}
