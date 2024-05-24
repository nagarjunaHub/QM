#!/bin/bash -x
export CC_WRAPPER=$(dirname $(readlink -f $0))/sonar_cc_wrapper.sh
export SCRIPT_BASE=$(dirname $(readlink -f $0))
path=${SCRIPT_BASE}
while [ "$path" != "/" ]; do
    if [ -d "$path/.repo" ]; then
        reporoot="$path"
        break
    fi
    path=$(dirname "$path")
done
if [ -n "$reporoot" ]; then
    echo "Found .repo directory at: $reporoot"
else
    echo "No .repo directory found."
    exit 1
fi
LOG_ROOT_DIR=${reporoot}/sonarqube_outdir
mkdir -p ${LOG_ROOT_DIR}
> ${LOG_ROOT_DIR}/aosp_build.log
> ${LOG_ROOT_DIR}/build_instructions.log
echo "$(date) - Find folder having sonar-project.properties " | tee -a ${LOG_ROOT_DIR}/build_instructions.log
for folder in $(find . -name sonar-project.properties -printf '%h\n' | sort -u)
do
    echo "$(date) - Touching all the files in the folder : $folder" >>  tee -a ${LOG_ROOT_DIR}/build_instructions.log
    find $folder -type f -exec touch {} \;
done
echo "$(date) - Running normal build" | tee -a ${LOG_ROOT_DIR}/build_instructions.log
cd ${reporoot}
source ./build/envsetup.sh >> ${LOG_ROOT_DIR}/aosp_build.log 2>&1
lunch shm_hmi-userdebug >> ${LOG_ROOT_DIR}/aosp_build.log 2>&1
lunch_exit_status=$?
source ./kernel_platform/qcom/proprietary/prebuilt_HY11/vendorsetup.sh >> ${LOG_ROOT_DIR}/aosp_build.log 2>&1
export CC_WRAPPER=$(dirname $(readlink -f $0))/sonar_cc_wrapper.sh
unset BUILD_NUMBER
unset USE_CCACHE
RECOMPILE_KERNEL=1 ./kernel_platform/build/android/prepare_vendor.sh autogvm gki >> ${LOG_ROOT_DIR}/aosp_build.log 2>&1
bash build.sh -j$(nproc) dist --target_only >> ${LOG_ROOT_DIR}/aosp_build.log 2>&1
make_exit_status=$?
echo "$(date) - Normal build results" | tee -a ${LOG_ROOT_DIR}/build_instructions.log
echo "$(date) - lunch exit status: $lunch_exit_status, make exit status: $make_exit_status" | tee -a ${LOG_ROOT_DIR}/build_instructions.log

