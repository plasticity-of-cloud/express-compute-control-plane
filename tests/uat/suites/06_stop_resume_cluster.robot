*** Settings ***
Documentation    Tests for stop-cluster and resume-cluster.

Library          ../libraries/MockServerLibrary.py
Resource         ../resources/common.resource

Suite Setup      Start Suite
Suite Teardown   Stop Mock Server


*** Variables ***
${PORT}         ${EMPTY}
# Store CLI flags in variables to prevent RF from treating them as named kwargs
${OPT_NO_WAIT}    --wait=false


*** Keywords ***
Start Suite
    ${p}=    Start Mock Server
    Set Suite Variable    ${PORT}    ${p}


*** Test Cases ***

Stop Cluster Returns Success
    [Setup]    Reset Mock Server
    Set Response    POST    /clusters/my-cluster/stop    202
    ...    {"clusterName":"my-cluster","status":"stopping"}
    ${r}=    Run CLI With Mock    ${PORT}    stop-cluster    my-cluster
    Should Succeed    ${r}
    Request Was Made    POST    /clusters/my-cluster/stop

Stop Cluster Not Found Exits Nonzero
    [Setup]    Reset Mock Server
    Set Response    POST    /clusters/ghost/stop    404
    ...    {"__type":"NotFoundException","message":"Cluster not found: ghost"}
    ${r}=    Run CLI With Mock    ${PORT}    stop-cluster    ghost
    Should Fail    ${r}
    Stderr Should Contain    ${r}    ghost

Resume Cluster Returns Success
    [Setup]    Reset Mock Server
    Set Response    POST    /clusters/my-cluster/resume    202
    ...    {"clusterName":"my-cluster","status":"resuming"}
    # Pass --wait=false via variable so RF doesn't parse it as a named kwarg
    ${r}=    Run CLI With Mock    ${PORT}    resume-cluster    my-cluster    ${OPT_NO_WAIT}
    Should Succeed    ${r}
    Request Was Made    POST    /clusters/my-cluster/resume

Resume Cluster Not Found Exits Nonzero
    [Setup]    Reset Mock Server
    Set Response    POST    /clusters/ghost/resume    404
    ...    {"__type":"NotFoundException","message":"Cluster not found: ghost"}
    ${r}=    Run CLI With Mock    ${PORT}    resume-cluster    ghost    ${OPT_NO_WAIT}
    Should Fail    ${r}
    Stderr Should Contain    ${r}    ghost
