SERVER_DIR=$PWD/server/src/main/resources/self-signed
CLIENT_DIR=$PWD/client/src/main/resources/self-signed
mkdir -p ${SERVER_DIR}
mkdir -p ${CLIENT_DIR}

SPACE_UUID=`uuidgen`
ORG_UUID=`uuidgen`

# Create CA certificate
openssl req -new -nodes -out ${SERVER_DIR}/ca.csr -keyout ${SERVER_DIR}/ca.key -subj "/CN=demo/O=tanzu/C=JP"
chmod og-rwx ${SERVER_DIR}/ca.key

cat <<EOF > ${SERVER_DIR}/ext_ca.txt
basicConstraints=CA:TRUE
keyUsage=digitalSignature,keyCertSign
EOF

openssl x509 -req -in ${SERVER_DIR}/ca.csr -days 3650 -signkey ${SERVER_DIR}/ca.key -out ${SERVER_DIR}/ca.crt -extfile ${SERVER_DIR}/ext_ca.txt

cat <<EOF > ${SERVER_DIR}/ext.txt
basicConstraints=CA:FALSE
keyUsage=digitalSignature,dataEncipherment,keyEncipherment,keyAgreement
extendedKeyUsage=serverAuth,clientAuth
subjectAltName = @alt_names
[alt_names]
DNS.1 = localhost
EOF

# Create Server certificate signed by CA
openssl req -new -nodes -out ${SERVER_DIR}/server.csr -keyout ${SERVER_DIR}/server.key -subj "/OU=app:`uuidgen`/OU=space:${SPACE_UUID}/OU=organization:${ORG_UUID}/CN=localhost"
chmod og-rwx ${SERVER_DIR}/server.key
openssl x509 -req -in ${SERVER_DIR}/server.csr -days 3650 -CA ${SERVER_DIR}/ca.crt -CAkey ${SERVER_DIR}/ca.key -CAcreateserial -out ${SERVER_DIR}/server.crt -extfile ${SERVER_DIR}/ext.txt

# Create Client certificate signed by CA
openssl req -new -nodes -out ${CLIENT_DIR}/client.csr -keyout ${CLIENT_DIR}/client.key -subj "/OU=app:`uuidgen`/OU=space:${SPACE_UUID}/OU=organization:${ORG_UUID}/CN=localhost"
# Test invalid certificate by creating a random space uuid
# openssl req -new -nodes -out ${CLIENT_DIR}/client.csr -keyout ${CLIENT_DIR}/client.key -subj "/OU=app:`uuidgen`/OU=space:`uuidgen`/OU=organization:${ORG_UUID}/CN=localhost"
chmod og-rwx ${CLIENT_DIR}/client.key
openssl x509 -req -in ${CLIENT_DIR}/client.csr -days 3650 -CA ${SERVER_DIR}/ca.crt -CAkey ${SERVER_DIR}/ca.key -CAcreateserial -out ${CLIENT_DIR}/client.crt -extfile ${SERVER_DIR}/ext.txt
cp ${SERVER_DIR}/ca.crt ${CLIENT_DIR}/ca.crt

# Copy client certificate

