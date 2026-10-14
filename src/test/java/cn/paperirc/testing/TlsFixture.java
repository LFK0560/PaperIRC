package cn.paperirc.testing;

import javax.net.ssl.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.util.*;

/** Test CA and leaves are generated outside production data, with no global trust-store changes. */
public final class TlsFixture {
    public final SSLContext valid,wrongHost,expired,client;
    public final Path directory;
    private static final char[] PASSWORD="test-password".toCharArray();
    private static TlsFixture singleton;
    public static synchronized TlsFixture get() throws Exception {if(singleton==null)singleton=new TlsFixture();return singleton;}
    private TlsFixture() throws Exception {
        directory=Files.createTempDirectory("paperirc-test-tls-");directory.toFile().deleteOnExit();
        Path ca=directory.resolve("ca.p12"),cert=directory.resolve("ca.crt");
        keytool("-genkeypair","-alias","ca","-keystore",ca.toString(),"-keyalg","RSA","-keysize","2048",
                "-dname","CN=PaperIRC Test CA","-ext","bc=ca:true","-ext","ku=keyCertSign,cRLSign","-validity","3650");
        keytool("-exportcert","-alias","ca","-keystore",ca.toString(),"-rfc","-file",cert.toString());
        valid=leaf("valid","localhost",false,ca,cert);wrongHost=leaf("wrong","wrong.example",false,ca,cert);
        expired=leaf("expired","localhost",true,ca,cert);
        KeyStore roots=KeyStore.getInstance("PKCS12");roots.load(null,PASSWORD);
        KeyStore root=load(ca);roots.setCertificateEntry("ca",root.getCertificate("ca"));
        TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(roots);
        client=SSLContext.getInstance("TLS");client.init(null,trust.getTrustManagers(),null);
    }
    private SSLContext leaf(String name,String hostname,boolean expired,Path ca,Path caCert) throws Exception {
        Path keys=directory.resolve(name+".p12"),csr=directory.resolve(name+".csr"),crt=directory.resolve(name+".crt");
        keytool("-genkeypair","-alias","server","-keystore",keys.toString(),"-keyalg","RSA","-keysize","2048","-dname","CN="+hostname,"-validity","365");
        keytool("-certreq","-alias","server","-keystore",keys.toString(),"-file",csr.toString());
        List<String> args=new ArrayList<>(List.of("-gencert","-alias","ca","-keystore",ca.toString(),"-infile",csr.toString(),"-outfile",crt.toString(),"-rfc",
                "-validity",expired?"1":"365","-ext","SAN=dns:"+hostname+(hostname.equals("localhost")?",ip:127.0.0.1":""),"-ext","KU=digitalSignature,keyEncipherment","-ext","EKU=serverAuth"));
        if(expired)args.addAll(List.of("-startdate","2020/01/01 00:00:00"));
        keytool(args.toArray(String[]::new));
        keytool("-importcert","-noprompt","-alias","ca","-keystore",keys.toString(),"-file",caCert.toString());
        keytool("-importcert","-noprompt","-alias","server","-keystore",keys.toString(),"-file",crt.toString());
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(load(keys),PASSWORD);
        SSLContext context=SSLContext.getInstance("TLS");context.init(km.getKeyManagers(),null,null);return context;
    }
    private static KeyStore load(Path f) throws Exception {
        KeyStore k=KeyStore.getInstance("PKCS12");try(InputStream in=Files.newInputStream(f)){k.load(in,PASSWORD);}return k;
    }
    private void keytool(String... args) throws Exception {
        List<String> command=new ArrayList<>();command.add(Path.of(System.getProperty("java.home"),"bin","keytool").toString());command.addAll(List.of(args));
        command.addAll(List.of("-storepass",new String(PASSWORD),"-keypass",new String(PASSWORD),"-storetype","PKCS12"));
        Process p=new ProcessBuilder(command).redirectErrorStream(true).start();String log=new String(p.getInputStream().readAllBytes());
        if(p.waitFor()!=0)throw new IOException("测试证书创建失败："+log);
        try(var files=Files.list(directory)){files.forEach(path->path.toFile().deleteOnExit());}
    }
}
