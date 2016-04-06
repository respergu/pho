package com.eharmony.services.mymatchesservice.service;

import java.util.Set;

import com.eharmony.services.mymatchesservice.rest.MatchFeedQueryContext;
import com.eharmony.services.mymatchesservice.util.MatchStatusEnum;
import com.eharmony.services.mymatchesservice.util.MatchStatusGroupEnum;

public class HBaseStoreFeedRequestContext extends BasicStoreFeedRequestContext {

    private MatchStatusGroupEnum matchStatusGroup;
    private Set<MatchStatusEnum> matchStatuses;

    public HBaseStoreFeedRequestContext(final MatchFeedQueryContext matchFeedQueryContext) {
        super(matchFeedQueryContext);
    }

    public MatchStatusGroupEnum getMatchStatusGroup() {
        return matchStatusGroup;
    }

    public void setMatchStatusGroup(MatchStatusGroupEnum matchStatusGroup) {
        this.matchStatusGroup = matchStatusGroup;
    }

    public Set<MatchStatusEnum> getMatchStatuses() {
        return matchStatuses;
    }

    public void setMatchStatuses(Set<MatchStatusEnum> matchStatuses) {
        this.matchStatuses = matchStatuses;
    }

}