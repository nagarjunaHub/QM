import com.eb.lib.aosp.aospUtils
import com.eb.lib.aosp.CommonEnvironment
import com.eb.lib.aosp.PipelineEnvironment
import groovy.json.JsonBuilder
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

def call(body) {
  // For pipeline common config
  def commonlib = new aospUtils()
  def config = body.script.config

  def configRuntime = body.script.configRuntime
  def target_id_list = configRuntime.affected_manifests.collect { it.replaceAll('.xml', '') }
  node(configRuntime.pipeline_node) {
      try {
        def build_type_list = ['userdebug']
        target_id_list.each { target_id ->
          build_type_list.each { build_type ->
            if ( config["supported_target_ids"][target_id][configRuntime.pipelineType] == "disabled" ) {
              commonlib.__INFO("WARNING: " + configRuntime.pipelineType + " is disabled for " + target_id + ". If you want, you can enable it in " + PROJECT_CONFIG_FILE)
              return
            }

            stackTarget = "StackTarget_${configRuntime.project_branch}_${target_id}_${build_type}"
            stackTargetFile = config.stacktarget_file
            build_target = [target_id,build_type].join("-")

            def snapshot_path = configRuntime.NET_SHAREDRIVE_TABLE[configRuntime.pipelineType] + "/" + configRuntime.project_release_version
            commonlib.__INFO("Snapshot_path: ${snapshot_path} for ${build_target}")
            def changeLogTypes = config[configRuntime.pipelineType].changeLogTypes

            node(configRuntime.build_variant[build_target].least_loaded_node) {
              /* def release_notes = sh(returnStdout: true, script: """#!/bin/bash -x
                cp ${snapshot_path}/release_note_*.log ${WORKSPACE}/ || true
              """) */
              copyArtifacts(projectName: env.JOB_NAME, filter: "release_note_*.log", selector: specific(env.BUILD_NUMBER),fingerprintArtifacts: true, target: "${WORKSPACE}/${env.BUILD_NUMBER}/")
              def attachmentList = sh(returnStdout: true, script: """#!/bin/bash -x
                ls -d ${WORKSPACE}/${env.BUILD_NUMBER}/release_note_*.log || true
              """)
              if(config[configRuntime.pipelineType].additional_recipients && config[configRuntime.pipelineType].additional_recipients.sendmail){
                config.email_recipients += ',' + config[configRuntime.pipelineType].additional_recipients.mail_ids
              }

              def nfs_files=sh(returnStdout: true, script: """#!/bin/bash -x
                ls -d ${snapshot_path}/* | grep -v proguard || true
              """)
              def mailBody = "New ${configRuntime.pipelineType} build ${configRuntime.project_release_version} available\n${configRuntime.pipelineType}_path:\n\n${nfs_files} \nAttached are the change logs.\nFor more info: ${BUILD_URL}"
              def subject = "New ${configRuntime.pipelineType} ${configRuntime.project_release_version} available"
              commonlib.ebEmail(subject, mailBody, config.default_email_sender, config.email_recipients, attachmentList)
              // emailext(body: email_body,
                 // attachmentsPattern: "**/release_note*.log",
                 /* to: config.email_recipients,
                    from: config.default_email_sender,
                    subject: "New ${configRuntime.pipelineType} ${configRuntime.project_release_version}/${build_target} available") */

            }
          }
        }
        if (config[configRuntime.pipelineType].nfs_cleanup != "null") {
          if (config[configRuntime.pipelineType].nfs_cleanup.enabled == "true") {
            def pipeline_nfs_dir = configRuntime.NET_SHAREDRIVE_TABLE[configRuntime.pipelineType]
            def apps_nfs_dir = configRuntime.NET_SHAREDRIVE_TABLE["apps"]
            def get_flash_nfs_dir = configRuntime.NET_SHAREDRIVE_TABLE["get_flash"]

            def days_to_keep = config[configRuntime.pipelineType].nfs_cleanup.older_than_days
            // Apps snapshot dir is cleaned up when the corresponding aosp snapshot is cleaned up.
            print("NFS Cleanup is enabled.")
            sh """#!/bin/bash
              source ${new PipelineEnvironment(this).loadBashLibs()} && prod_release_clean_up ${pipeline_nfs_dir} ${days_to_keep}
              source ${new PipelineEnvironment(this).loadBashLibs()} && prod_release_clean_up ${apps_nfs_dir} ${days_to_keep}
              source ${new PipelineEnvironment(this).loadBashLibs()} && prod_release_clean_up ${get_flash_nfs_dir} 2
            """
          } else {
            print("NFS Cleanup is disabled in the pipeline config file.")
          }
        } else {
          print("NFS Cleanup not configured in the pipeline config file.")
        }

      } catch (FlowInterruptedException e) {
        throw e
      } catch(err) {
        commonlib.__INFO("Failures from this stage are not fatal. So ignoring them.")
        commonlib.__INFO(err.toString())
      }
  }
}
