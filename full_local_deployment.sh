#./build-local.sh --only tenant,cli,cdk --native --skip-tests
#./deploy-local.sh --skip-build --context nativeArch=x86
#./build-local.sh
#./deploy-local.sh --skip-build --context jvmTenant=true

./build-local.sh --only tenant,cli,cdk --skip-tests
./deploy-local.sh --skip-build --context jvmTenant=true
