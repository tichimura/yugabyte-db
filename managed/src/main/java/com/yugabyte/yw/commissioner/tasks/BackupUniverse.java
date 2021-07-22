/*
 * Copyright 2019 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 *     https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.commissioner.tasks;

import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.commissioner.SubTaskGroupQueue;
import com.yugabyte.yw.commissioner.UserTaskDetails;
import com.yugabyte.yw.forms.BackupTableParams;
import com.yugabyte.yw.models.KmsConfig;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.PlatformMetrics;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BackupUniverse extends UniverseTaskBase {

  @Inject
  protected BackupUniverse(BaseTaskDependencies baseTaskDependencies) {
    super(baseTaskDependencies);
  }

  @Override
  protected BackupTableParams taskParams() {
    return (BackupTableParams) taskParams;
  }

  @Override
  public void run() {

    Universe universe = Universe.getOrBadRequest(taskParams().universeUUID);
    try {
      checkUniverseVersion();
      // Create the task list sequence.
      subTaskGroupQueue = new SubTaskGroupQueue(userTaskUUID);
      if (universe.getUniverseDetails().backupInProgress) {
        throw new RuntimeException("A backup for this universe is already in progress.");
      }

      // Update the universe DB with the update to be performed and set the 'updateInProgress' flag
      // to prevent other updates from happening.
      lockUniverse(-1 /* expectedUniverseVersion */);

      UserTaskDetails.SubTaskGroupType groupType;
      if (taskParams().actionType == BackupTableParams.ActionType.CREATE) {
        groupType = UserTaskDetails.SubTaskGroupType.CreatingTableBackup;
        createEncryptedUniverseKeyBackupTask().setSubTaskGroupType(groupType);
        updateBackupState(true);
        unlockUniverseForUpdate();
      } else if (taskParams().actionType == BackupTableParams.ActionType.RESTORE) {
        groupType = UserTaskDetails.SubTaskGroupType.RestoringTableBackup;

        // Handle case of backup being encrypted at rest
        if (KmsConfig.get(taskParams().kmsConfigUUID) != null) {
          // Download universe keys backup file for encryption at rest
          BackupTableParams restoreKeysParams = new BackupTableParams();
          restoreKeysParams.storageLocation = taskParams().storageLocation;
          restoreKeysParams.universeUUID = taskParams().universeUUID;
          restoreKeysParams.storageConfigUUID = taskParams().storageConfigUUID;
          restoreKeysParams.kmsConfigUUID = taskParams().kmsConfigUUID;
          restoreKeysParams.actionType = BackupTableParams.ActionType.RESTORE_KEYS;
          createTableBackupTask(restoreKeysParams).setSubTaskGroupType(groupType);

          // Restore universe keys backup file for encryption at rest
          createEncryptedUniverseKeyRestoreTask(taskParams()).setSubTaskGroupType(groupType);
        }
      } else {
        throw new RuntimeException("Invalid backup action type: " + taskParams().actionType);
      }

      createTableBackupTask(taskParams()).setSubTaskGroupType(groupType);

      // Marks the update of this universe as a success only if all the tasks before it succeeded.
      createMarkUniverseUpdateSuccessTasks()
          .setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.ConfigureUniverse);

      Set<String> tableNames =
          taskParams()
              .getTableNames()
              .stream()
              .map(tableName -> taskParams().getKeyspace() + ":" + tableName)
              .collect(Collectors.toSet());

      taskInfo = String.join(",", tableNames);

      // Run all the tasks.
      subTaskGroupQueue.run();

      metricService.setOkStatusMetric(
          metricService.buildMetricTemplate(PlatformMetrics.CREATE_BACKUP_STATUS, universe));
      if (taskParams().actionType != BackupTableParams.ActionType.CREATE) {
        unlockUniverseForUpdate();
      }
    } catch (Throwable t) {
      log.error("Error executing task {} with error='{}'.", getName(), t.getMessage(), t);

      metricService.setStatusMetric(
          metricService.buildMetricTemplate(PlatformMetrics.CREATE_BACKUP_STATUS, universe),
          t.getMessage());
      // Run an unlock in case the task failed before getting to the unlock. It is okay if it
      // errors out.
      unlockUniverseForUpdate();
      throw t;
    } finally {
      if (taskParams().actionType == BackupTableParams.ActionType.CREATE) {
        updateBackupState(false);
      }
    }

    log.info("Finished {} task.", getName());
  }
}
