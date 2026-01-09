# Play with Spring GRPC with mTLS (netty)

## 1. Create Server/Client certificate 

```
bash -x certificate.sh
```

## 2. Run Server

```
cd server
```

Run the following to generate classes
```
./mvnw compile
```
Run the app
```
./mvnw spring-boot:run
```

## 3. Run Client

```
cd ../client
```

Run the following to generate classes
```
./mvnw compile
```
Run the app
```
./mvnw spring-boot:run
```