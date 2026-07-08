*** Settings ***
Documentation    Full end-to-end lifecycle UAT against a deployed AWS stack.
...
...              REQUIRES: UAT_LIVE=true, deployed stack, valid AWS credentials.
...              Skipped automatically when UAT_LIVE != true.

Resource         ../../resources/common.resource

Suite Setup      Skip If Not Live


*** Variables ***
${UAT_CLUSTER}    uat-test
${UAT_ROLE_ARN}   %{UAT_ROLE_ARN}


*** Keywords ***
Skip If Not Live
    ${live}=    Get Environment Variable    UAT_LIVE    default=false
    Skip If    '${live}' != 'true'
    ...    Live UAT skipped. Set UAT_LIVE=true to run against a deployed stack.


*** Test Cases ***

Create Cluster And Wait For Ready
    [Tags]    live    slow
    ${r}=    Run CLI    create-cluster    ${UAT_CLUSTER}    --wait
    Should Succeed    ${r}
    Stdout Should Contain    ${r}    ready

Get Cluster Access Returns IP And SSH Command
    [Tags]    live
    ${r}=    Run CLI    get-cluster-access    ${UAT_CLUSTER}
    Should Succeed    ${r}
    Stdout Should Contain    ${r}    ssh
    Stdout Should Contain    ${r}    ec2-user

Get Cluster Access Save Key Writes PEM File
    [Tags]    live
    ${r}=    Run CLI    get-cluster-access    ${UAT_CLUSTER}    --save-key
    Should Succeed    ${r}
    Stdout Should Contain    ${r}    SSH key saved to

Create And List Association
    [Tags]    live
    ${r}=    Run CLI    create-association    ${UAT_CLUSTER}    default    uat-sa    ${UAT_ROLE_ARN}
    Should Succeed    ${r}
    ${r2}=    Run CLI    list-associations    ${UAT_CLUSTER}
    Should Succeed    ${r2}
    Stdout Should Contain    ${r2}    uat-sa

Stop And Resume Cluster
    [Tags]    live    slow
    ${r}=    Run CLI    stop-cluster    ${UAT_CLUSTER}
    Should Succeed    ${r}
    ${r2}=    Run CLI    resume-cluster    ${UAT_CLUSTER}
    Should Succeed    ${r2}

Delete Cluster And Verify Gone
    [Tags]    live
    ${r}=    Run CLI    delete-cluster    ${UAT_CLUSTER}
    Should Succeed    ${r}
    ${r2}=    Run CLI    list-clusters
    Should Succeed    ${r2}
    Should Not Contain    ${r2.stdout}    ${UAT_CLUSTER}
