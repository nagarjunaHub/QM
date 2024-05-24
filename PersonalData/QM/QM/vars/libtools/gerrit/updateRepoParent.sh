#!/bin/bash
WORKSPACE=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
all_links=""
ssh -p 29418 t2k-gerrit gerrit flush-caches --all
while IFS= read -r link || [[ -n "$link" ]]; do
    link=${link/.git/}
    link=${link/ssh:\/\/*.conti.de:29418/T2K\/qnx}
    link=${link/ssh:\/\/*.conti.de/T2K\/qnx}
    if [[ "x${link}" != "x" ]]; then
        all_links="${link} ${all_links}"
    fi
done < "${WORKSPACE}/list_links"

for link in ${all_links[@]}; do
    echo "Set for ${link}"
    ssh -p 29418 t2k-gerrit gerrit set-project-parent "${link}" --parent All-Devs+Conti
done