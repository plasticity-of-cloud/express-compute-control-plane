*** Settings ***
Documentation    Tests for create-association, list-associations,
...              describe-association, delete-association.
...
...              All association commands use the management API endpoint
...              (EKS_DX_ENDPOINT), not the provisioning URL.
...
...              Note: CLI flags (--cluster-name etc.) are stored in ${} variables
...              to prevent Robot Framework from treating them as named kwargs.

Library          ../libraries/MockServerLibrary.py
Resource         ../resources/common.resource

Suite Setup      Start Suite
Suite Teardown   Stop Mock Server


*** Variables ***
${PORT}         ${EMPTY}
${ROLE_ARN}     arn:aws:iam::123456789012:role/my-app-role
# Store CLI flag strings in variables so RF doesn't parse them as named kwargs
${OPT_CLUSTER}          --cluster-name
${OPT_NS}               --namespace
${OPT_SA}               --service-account
${OPT_ROLE}             --role-arn
${OPT_ASSOC_ID}         --association-id


*** Keywords ***
Start Suite
    ${p}=    Start Mock Server
    Set Suite Variable    ${PORT}    ${p}


*** Test Cases ***

Create Association Happy Path
    [Setup]    Reset Mock Server
    Set Response    POST    /clusters/my-cluster/pod-identity-associations    201
    ...    {"associationId":"assoc123","namespace":"default","serviceAccount":"my-sa","roleArn":"${ROLE_ARN}","trustPolicyStatus":"APPLIED"}
    ${r}=    Run CLI With Mock    ${PORT}
    ...    create-association
    ...    ${OPT_CLUSTER}    my-cluster
    ...    ${OPT_NS}         default
    ...    ${OPT_SA}         my-sa
    ...    ${OPT_ROLE}       ${ROLE_ARN}
    Should Succeed    ${r}
    Request Was Made    POST    /clusters/my-cluster/pod-identity-associations
    ${body}=    Get Last Request Body    POST    /clusters/my-cluster/pod-identity-associations
    Should Contain    ${body}    default
    Should Contain    ${body}    my-sa
    Should Contain    ${body}    ${ROLE_ARN}

Create Association Missing Args Exits Nonzero
    ${r}=    Run CLI With Mock    ${PORT}    create-association    ${OPT_CLUSTER}    my-cluster
    Should Fail    ${r}

Create Association Cluster Not Found Exits Nonzero
    [Setup]    Reset Mock Server
    Set Response    POST    /clusters/ghost/pod-identity-associations    404
    ...    {"__type":"NotFoundException","message":"Cluster not found: ghost"}
    ${r}=    Run CLI With Mock    ${PORT}
    ...    create-association
    ...    ${OPT_CLUSTER}    ghost
    ...    ${OPT_NS}         default
    ...    ${OPT_SA}         my-sa
    ...    ${OPT_ROLE}       ${ROLE_ARN}
    Should Fail    ${r}
    Stderr Should Contain    ${r}    ghost

List Associations Returns Output
    [Setup]    Reset Mock Server
    Set Response    GET    /clusters/my-cluster/pod-identity-associations    200
    ...    {"associations":[{"associationId":"assoc123","namespace":"default","serviceAccount":"my-sa","roleArn":"${ROLE_ARN}"}]}
    ${r}=    Run CLI With Mock    ${PORT}    list-associations    ${OPT_CLUSTER}    my-cluster
    Should Succeed    ${r}

List Associations Empty Returns Success
    [Setup]    Reset Mock Server
    Set Response    GET    /clusters/my-cluster/pod-identity-associations    200
    ...    {"associations":[]}
    ${r}=    Run CLI With Mock    ${PORT}    list-associations    ${OPT_CLUSTER}    my-cluster
    Should Succeed    ${r}

Delete Association Returns Success
    [Setup]    Reset Mock Server
    Set Response    DELETE    /clusters/my-cluster/pod-identity-associations/assoc123    204    ${EMPTY}
    ${r}=    Run CLI With Mock    ${PORT}
    ...    delete-association
    ...    ${OPT_CLUSTER}      my-cluster
    ...    ${OPT_ASSOC_ID}     assoc123
    Should Succeed    ${r}
    Request Was Made    DELETE    /clusters/my-cluster/pod-identity-associations/assoc123

Delete Association Not Found Exits Nonzero
    [Setup]    Reset Mock Server
    Set Response    DELETE    /clusters/my-cluster/pod-identity-associations/ghost    404
    ...    {"__type":"NotFoundException","message":"Association not found"}
    ${r}=    Run CLI With Mock    ${PORT}
    ...    delete-association
    ...    ${OPT_CLUSTER}      my-cluster
    ...    ${OPT_ASSOC_ID}     ghost
    Should Fail    ${r}
