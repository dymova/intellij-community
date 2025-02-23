// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.updateSettings.impl;

import com.intellij.ide.IdeBundle;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.*;
import com.intellij.openapi.application.ex.ApplicationInfoEx;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.updateSettings.UpdateStrategyCustomization;
import com.intellij.openapi.util.BuildNumber;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.text.DateFormatUtil;
import org.jdom.JDOMException;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static java.lang.Math.max;

/**
 * @author yole
 */
public final class UpdateCheckerComponent implements Runnable {
  public static UpdateCheckerComponent getInstance() {
    return ApplicationManager.getApplication().getComponent(UpdateCheckerComponent.class);
  }

  static final String SELF_UPDATE_STARTED_FOR_BUILD_PROPERTY = "ide.self.update.started.for.build";

  private static final Logger LOG = Logger.getInstance(UpdateCheckerComponent.class);

  private static final long CHECK_INTERVAL = DateFormatUtil.DAY;
  private static final String ERROR_LOG_FILE_NAME = "idea_updater_error.log"; // must be equal to com.intellij.updater.Runner.ERROR_LOG_FILE_NAME

  private volatile ScheduledFuture<?> myScheduledCheck;

  public UpdateCheckerComponent() {
    Application app = ApplicationManager.getApplication();
    if (!app.isCommandLine()) {
      app.executeOnPooledThread(() -> {
        checkIfPreviousUpdateFailed();

        updateDefaultChannel();
        scheduleFirstCheck();
        snapPackageNotification();

        UpdateInstaller.cleanupPatch();
      });
    }
  }

  @Override
  public void run() {
    UpdateChecker.updateAndShowResult().doWhenProcessed(() -> queueNextCheck(CHECK_INTERVAL));
  }

  public void queueNextCheck() {
    queueNextCheck(CHECK_INTERVAL);
  }

  public void cancelChecks() {
    ScheduledFuture<?> future = myScheduledCheck;
    if (future != null) future.cancel(false);
  }

  private static void checkIfPreviousUpdateFailed() {
    PropertiesComponent properties = PropertiesComponent.getInstance();
    if (ApplicationInfo.getInstance().getBuild().asString().equals(properties.getValue(SELF_UPDATE_STARTED_FOR_BUILD_PROPERTY)) &&
        new File(PathManager.getLogPath(), ERROR_LOG_FILE_NAME).length() > 0) {
      IdeUpdateUsageTriggerCollector.trigger("update.failed");
      LOG.info("The previous IDE update failed");
    }
    properties.setValue(SELF_UPDATE_STARTED_FOR_BUILD_PROPERTY, null);
  }

  private static void updateDefaultChannel() {
    UpdateSettings settings = UpdateSettings.getInstance();
    ChannelStatus current = settings.getSelectedChannelStatus();
    LOG.info("channel: " + current.getCode());
    boolean eap = ApplicationInfoEx.getInstanceEx().isMajorEAP();

    if (eap && current != ChannelStatus.EAP && UpdateStrategyCustomization.getInstance().forceEapUpdateChannelForEapBuilds()) {
      settings.setSelectedChannelStatus(ChannelStatus.EAP);
      LOG.info("channel forced to 'eap'");
      if (!ConfigImportHelper.isFirstSession()) {
        String title = IdeBundle.message("update.notifications.title");
        String message = IdeBundle.message("update.channel.enforced", ChannelStatus.EAP);
        UpdateChecker.NOTIFICATIONS.createNotification(title, message, NotificationType.INFORMATION, null).notify(null);
      }
    }

    if (!eap && current == ChannelStatus.EAP && ConfigImportHelper.isConfigImported()) {
      settings.setSelectedChannelStatus(ChannelStatus.RELEASE);
      LOG.info("channel set to 'release'");
    }
  }

  private void scheduleFirstCheck() {
    UpdateSettings settings = UpdateSettings.getInstance();
    if (!settings.isCheckNeeded()) {
      return;
    }

    BuildNumber currentBuild = ApplicationInfo.getInstance().getBuild();
    BuildNumber lastBuildChecked = BuildNumber.fromString(settings.getLastBuildChecked());
    long timeSinceLastCheck = max(System.currentTimeMillis() - settings.getLastTimeChecked(), 0);

    if (lastBuildChecked == null || currentBuild.compareTo(lastBuildChecked) > 0 || timeSinceLastCheck >= CHECK_INTERVAL) {
      run();
    }
    else {
      queueNextCheck(CHECK_INTERVAL - timeSinceLastCheck);
    }
  }

  private void queueNextCheck(long delay) {
    myScheduledCheck = AppExecutorUtil.getAppScheduledExecutorService().schedule(this, delay, TimeUnit.MILLISECONDS);
  }

  private static void snapPackageNotification() {
    UpdateSettings settings = UpdateSettings.getInstance();
    if (!settings.isCheckNeeded() || ExternalUpdateManager.ACTUAL != ExternalUpdateManager.SNAP) {
      return;
    }

    BuildNumber currentBuild = ApplicationInfo.getInstance().getBuild();
    BuildNumber lastBuildChecked = BuildNumber.fromString(settings.getLastBuildChecked());
    if (lastBuildChecked == null) {
      /* First IDE start, just save info about build */
      UpdateSettings.getInstance().saveLastCheckedInfo();
      return;
    }

    /* Show notification even in case of downgrade */
    if (!currentBuild.equals(lastBuildChecked)) {
      UpdatesInfo updatesInfo = null;
      try {
        updatesInfo = UpdateChecker.getUpdatesInfo();
      }
      catch (IOException | JDOMException e) {
        LOG.warn(e);
      }

      String blogPost = null;
      if (updatesInfo != null) {
        Product product = updatesInfo.get(currentBuild.getProductCode());
        if (product != null) {
          outer:
          for (UpdateChannel channel : product.getChannels()) {
            for (BuildInfo build : channel.getBuilds()) {
              if (currentBuild.equals(build.getNumber())) {
                blogPost = build.getBlogPost();
                break outer;
              }
            }
          }
        }
      }

      String title = IdeBundle.message("update.notifications.title");
      String message = blogPost == null ? IdeBundle.message("update.snap.message")
                                        : IdeBundle.message("update.snap.message.with.blog.post", StringUtil.escapeXmlEntities(blogPost));
      UpdateChecker.NOTIFICATIONS.createNotification(
        title, message, NotificationType.INFORMATION, NotificationListener.URL_OPENING_LISTENER).notify(null);

      UpdateSettings.getInstance().saveLastCheckedInfo();
    }
  }
}