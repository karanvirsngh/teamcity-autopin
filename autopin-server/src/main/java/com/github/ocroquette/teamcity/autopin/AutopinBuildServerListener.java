package com.github.ocroquette.teamcity.autopin;

import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.serverSide.impl.LogUtil;
import jetbrains.buildServer.users.User;
import jetbrains.buildServer.util.EventDispatcher;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;


public class AutopinBuildServerListener extends BuildServerAdapter {
    public static final String TAG_PIN = "autopin";
    public static final String TAG_PIN_INCLUDE_DEPENDENCIES = "autopin_include_dependencies";

    private final org.apache.log4j.Logger LOG = org.apache.log4j.Logger.getLogger(Loggers.SERVER_CATEGORY);
    private final BuildHistory buildHistory;


    public AutopinBuildServerListener(@NotNull EventDispatcher<BuildServerListener> events,
                                      @NotNull BuildHistory buildHistory) {
        LOG.info("AutopinBuildServerListener constructor");
        events.addListener(this);
        this.buildHistory = buildHistory;
    }

    @Override
    public void buildFinished(@NotNull SRunningBuild build) {
        Loggers.SERVER.info("buildFinished: " + LogUtil.describe(build));

        final SFinishedBuild finishedBuild = buildHistory.findEntry(build.getBuildId());

        User triggeringUser = build.getTriggeredBy().getUser();

        if (finishedBuild.getTags().contains(TAG_PIN) || finishedBuild.getTags().contains(TAG_PIN_INCLUDE_DEPENDENCIES)) {

            String comment = "Pinned automatically based on service message (" + TAG_PIN + ") in build #" + finishedBuild.getBuildId();

            finishedBuild.setPinned(true, triggeringUser, comment);

            if (finishedBuild.getTags().contains(TAG_PIN_INCLUDE_DEPENDENCIES)) {
                List<? extends BuildPromotion> allDependencies = finishedBuild.getBuildPromotion().getAllDependencies();

                for (BuildPromotion bp : allDependencies) {
                    LOG.info("Pinning dependency: " + bp.getAssociatedBuild());
                    buildHistory.findEntry(bp.getAssociatedBuild().getBuildId()).setPinned(true, triggeringUser, comment);
                }
            }

            BuildTagHelper.removeTag(finishedBuild, TAG_PIN);
            BuildTagHelper.removeTag(finishedBuild, TAG_PIN_INCLUDE_DEPENDENCIES);
        }

        for (SBuildFeatureDescriptor bfd : finishedBuild.getBuildFeaturesOfType(AutoPinBuildFeature.TYPE)) {
            if (areConditionsMet(bfd.getParameters(), finishedBuild)) {
                String comment = bfd.getParameters().get(AutoPinBuildFeature.PARAM_COMMENT);
                finishedBuild.setPinned(true, triggeringUser, comment);

                if (StringUtils.isTrue(bfd.getParameters().get(AutoPinBuildFeature.PARAM_PIN_DEPENDENCIES))) {
                    for (BuildPromotion bp : finishedBuild.getBuildPromotion().getAllDependencies()) {
                        buildHistory.findEntry(bp.getAssociatedBuild().getBuildId()).setPinned(true, triggeringUser, comment);
                    }
                }
            }
        }
    }

    private boolean areConditionsMet(Map<String, String> buildFeatureParameters, SFinishedBuild build) {
        boolean matching = true;

        String requestedStatus = buildFeatureParameters.get(AutoPinBuildFeature.PARAM_STATUS);
        if (requestedStatus != null) {
            if (requestedStatus.equals(AutoPinBuildFeature.PARAM_STATUS_SUCCESSFUL)) {
                matching = matching && build.getBuildStatus().equals(Status.NORMAL);
            } else if (requestedStatus.equals(AutoPinBuildFeature.PARAM_STATUS_FAILED)) {
                matching = matching && !build.getBuildStatus().equals(Status.NORMAL);
            }
        }

        String requestedBranchPattern = buildFeatureParameters.get(AutoPinBuildFeature.PARAM_BRANCH_PATTERN);
        if (requestedBranchPattern != null && !requestedBranchPattern.isEmpty()) {
            matching = matching && build.getBranch().getName().matches(requestedBranchPattern);
        }

        return matching;
    }
}
