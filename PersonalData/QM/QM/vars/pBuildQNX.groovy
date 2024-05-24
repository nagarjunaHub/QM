import com.eb.lib.jobUtils

def call(Map parameters) {
    def script = parameters.script
    def stage = parameters.stage
    def variant = parameters.variant
    def configMap = script.configMap
    def stageConfig = configMap.stageConfig
    def variantConfig = configMap.variantConfig[variant]
/***************************** End of common part *****************************/
    dir(variantConfig.workingDir) {
        docker.image(variantConfig.dockerImage).inside(variantConfig.dockerArgs) {
            def prebuitPath = variantConfig.androidPrebuitPath
            def buildTarget = variantConfig.qnxBuildTarget
            def androidGuest = variantConfig.androidGuest
            if(configMap.env.GERRIT_EVENT_COMMENT_TEXT) {
                if(new jobUtils().base64Decode(configMap.env.GERRIT_EVENT_COMMENT_TEXT).trim().contains('_iip_int_')){
                    prebuitPath = variantConfig.androidPrebuitPathIIPInt
                }
            }

            new jobUtils().scriptRun("""
cd /ssd/jenkins/workdir
echo \$(basename \$(find .repo/manifests -name "DN_*.xml")) > iipCurrentBaseline
export isCleanBuild=\$(diff ./iipCurrentBaseline ./iipPreviousBaseline &>/dev/null && echo "false" || echo "true")
git --git-dir=.aosp-manifest-release/.git remote update || true
export AOSP_BASELINE="\$(git --git-dir=.aosp-manifest-release/.git describe --tags || true)"
export BUILD_DIR=workdir
rm -rf \${BUILD_DIR}/${androidGuest} \${BUILD_DIR}/bitbake.lock \${BUILD_DIR}/downloads/repo

#./meta-distro-common/scripts/setup_buildenv.py -d base-qnx -b \${BUILD_DIR} -m qnx7qam8255-som -a meta-sw-shm -a meta-android-guest
./meta-distro-common/scripts/setup_buildenv.py -d base-qnx -b \${BUILD_DIR} -m ${buildTarget} -a meta-sw-shm
 source poky/oe-init-build-env  \${BUILD_DIR}

#if [[ ${configMap.isCleanBuild} == true ]] || [[ \${isCleanBuild} == 'true' ]]; then
  rm -rf tmp-distro-base-qnx qdashboard
#else
#  if [ -f tmp-distro-base-qnx/deploy/distro-base-qnx/images/qnx7shm-qam8255/initial_loading.zip ]; then
#    for cleanIncrementalPkg in ${variantConfig.incrementalPackages?:''}; do
#      bitbake -c cleansstate \${cleanIncrementalPkg} || true  # Result of this clean can be ignored, there some cases of recipes got removed.
#    done
#  fi
#fi
export ANDROID_GUEST_PREBUILDS_DIR="${prebuitPath}"
export ANDROID_GUEST_PREBUILDS_FILE="prebuilts.tar.gz"
export BUILD_VERSION="QNX:${variantConfig.buildVersion}###AOSP:\${AOSP_BASELINE}"
export ENV_VARS_EXPORT="BUILD_NUMBER JOB_NAME BUILD_VERSION BASED_ON PYTHONDONTWRITEBYTECODE DOXYGEN_DOCUMENTATION QUALITY_DASHBOARD ANDROID_GUEST_PREBUILDS_DIR ANDROID_GUEST_PREBUILDS_FILE "
export BB_ENV_PASSTHROUGH_ADDITIONS="\${BB_ENV_PASSTHROUGH_ADDITIONS} \${ENV_VARS_EXPORT}"
export QUALITY_DASHBOARD=true
bitbake base-qnx-image
#bitbake world -k
#bitbake package-index
bitbake system-deployment
            """, variantConfig.scriptNamePrefix)
        }
    }
}
