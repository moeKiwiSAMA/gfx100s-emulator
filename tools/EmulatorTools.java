import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;

/** Repository commands without a shell. Bootstrap: Java 17+; emulator/build: JDK 25. */
public final class EmulatorTools {
    private static Path repo;
    private static final String MAIN = "com.github.nyabanban.fuji.emulator.desktop.MainKt";
    private static final String TOOLS = "com.github.nyabanban.fuji.emulator.desktop.tools.";
    private static final String[] COMMON = {"--repo", "--java-home", "--java-temp-directory", "--gradle-home", "--firmware", "--native-library"};
    private static Map<String,String> options;
    private static boolean dryRun;
    private static boolean inProcessGradle;
    private static String currentCommand;
    private static Map<String,Object> dryRunPreparation;
    private static final class ProcessFailure extends Exception { final int exitCode; ProcessFailure(int exitCode) { super("Child process exited with code "+exitCode); this.exitCode=exitCode; } }
    @FunctionalInterface interface CheckedAction { void run() throws Exception; }
    static void collectorPipeline(CheckedAction prepare,CheckedAction snapshot,CheckedAction start) throws Exception { prepare.run();snapshot.run();start.run(); }
    static void checkProcessExit(int exit) throws ProcessFailure { if(exit!=0)throw new ProcessFailure(exit); }
    public static void main(String[] args) {
        try {
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out),true,StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err),true,StandardCharsets.UTF_8));
            execute(args); }
        catch (ProcessFailure error) { System.err.println("Project tool failed: " + error.getMessage()); System.exit(error.exitCode); }
        catch (Exception error) { System.err.println("Project tool failed: " + error.getMessage()); System.exit(1); }
    }
    static void execute(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) { help(); return; }
        currentCommand=args[0]; dryRun=false; inProcessGradle=false; dryRunPreparation=null;
        options = new LinkedHashMap<>(); var rest = new ArrayList<String>();
        for (int i=1; i<args.length; i++) {
            String arg=args[i];
            if (arg.equals("--")) { rest.addAll(Arrays.asList(args).subList(i+1,args.length)); break; }
            if (arg.equals("--python")) throw new IllegalArgumentException("--python has been retired; research and research-tests execute JVM code");
            if (Arrays.asList(COMMON).contains(arg)) {
                if (++i==args.length || options.put(arg,args[i])!=null) throw new IllegalArgumentException("Missing/duplicate option: " + arg);
            } else if (arg.equals("--dry-run")) { if(dryRun) throw new IllegalArgumentException("Duplicate --dry-run"); dryRun=true; }
            else if(arg.equals("--in-process-gradle")) inProcessGradle=true;
            else rest.add(arg);
        }
        repo=Path.of(options.getOrDefault("--repo",System.getProperty("user.dir"))).toAbsolutePath().normalize();
        if (!Files.isRegularFile(repo.resolve("settings.gradle.kts"))) throw new IllegalArgumentException("Run from repository root or supply --repo PATH");
        if(options.containsKey("--java-home"))options.put("--java-home",normalizeJdkHome(absolute(options.get("--java-home"))).toString());
        switch(args[0]) {
            case "build" -> gradle(rest.isEmpty()?List.of("build","stageRuntime"):rest);
            case "prepare" -> { noArgs(rest); gradle(List.of("prepareRuntime")); }
            case "launch" -> launch(MAIN, rest.isEmpty()?List.of("--home",repo.resolve("local/runtime").toString()):rest);
            case "collect" -> launch(TOOLS+"JvmEvidenceCollector",rest);
            case "verify" -> launch(TOOLS+"JvmEvidenceVerifier",rest);
            case "compare-runs" -> launch(TOOLS+"JvmEvidenceComparison",rest);
            case "metadata" -> metadata(rest);
            case "http" -> httpCommand(rest);
            case "setup-jvm" -> setup(rest);
            case "setup-native" -> { noArgs(rest); gradle(List.of(":cpu-dynarmic:buildDynarmicNative")); }
            case "research-tests" -> { var tasks=new ArrayList<String>(List.of(":desktop:test","--tests","*Research*"));tasks.addAll(rest);gradle(tasks); }
            case "research" -> research(rest);
            case "self-test" -> { noArgs(rest); selfTest(); }
            default -> throw new IllegalArgumentException("Unknown command: "+args[0]);
        }
    }
    private static void research(List<String> rest) throws Exception {
        int commandIndex=0;while(commandIndex<rest.size()&&rest.get(commandIndex).startsWith("--"))commandIndex+=2;
        String command=commandIndex<rest.size()?rest.get(commandIndex):"";
        var args=new ArrayList<String>(List.of("--repo",repo.toString()));
        if(Set.of("inspect","scan","loader-observations","all").contains(command)&&options.containsKey("--firmware"))args.addAll(List.of("--firmware",absolute(options.get("--firmware")).toString()));
        if(command.equals("collect")){
            args.addAll(rest.subList(0,commandIndex+1));
            for(String key:List.of("--firmware","--native-library","--java-home","--java-temp-directory"))if(options.containsKey(key))args.addAll(List.of(key,absolute(options.get(key)).toString()));
            args.addAll(rest.subList(commandIndex+1,rest.size()));
        }else args.addAll(rest);
        launch("com.github.nyabanban.fuji.emulator.desktop.research.ResearchMain",args,command.equals("collect"));
    }
    private static void noArgs(List<String> args) { if(!args.isEmpty()) throw new IllegalArgumentException("Unexpected arguments: "+args); }
    private static void help() {
        System.out.println("""
            sh jvm.sh [COMMAND] [OPTIONS] [-- ARGUMENTS] (default command: build)
            Java diagnostic entry: java tools/EmulatorTools.java COMMAND [OPTIONS]
              build [Gradle tasks]       default: build stageRuntime
              prepare                    stage configuration/JARs to local/runtime
              launch [app arguments]     execute staged JVM application directly
              collect [collector flags]  prepare current tools, isolate bootstrap, then audit a NEW directory
              verify --run DIRECTORY     verify an immutable audit bundle
              compare-runs --before DIR --after DIR --output FILE  compare completed migration observations
              metadata [--check]          generate/check metadata from the YAML dictionary
              setup-jvm [--version 25] [--architecture x64|aarch64]
              setup-native [arguments]   pure Java verified native/header installation helper
              research [arguments]       JVM inspect/scan/loader-observations/all; --firmware DAT --output NEW_JSON
              http METHOD URL [--body-file PATH]  loopback HTTP configuration/debug API
              research-tests [Gradle flags]  JVM JUnit :desktop:test --tests *Research*
              self-test                  safe archive/parser/metadata regression checks
            Common: --repo PATH --java-home JDK25 --java-temp-directory SHORT_PATH
                    --gradle-home PATH --firmware DAT --native-library LIB
                    --dry-run --in-process-gradle
            Requires initial Java 17+ even for setup-jvm. Build/run require JDK 25.
            Portable shell frontend: Cygwin on Windows, POSIX sh on Linux/macOS.
            Java backend: argument arrays, no cmd/PowerShell/batch or command strings.
            Use -- before app arguments to pass a colliding common option verbatim.
            """);
    }
    private static Path javaHome() throws IOException {
        var candidates=new ArrayList<Path>();
        if(options.containsKey("--java-home")) candidates.add(Path.of(options.get("--java-home")));
        else {
            String env=System.getenv("JAVA_HOME"); if(env!=null&&!env.isBlank()) candidates.add(Path.of(env));
            if(Files.isDirectory(repo.resolve(".tools"))) try(var files=Files.list(repo.resolve(".tools"))) { files.filter(p->p.getFileName().toString().startsWith("jdk-25")).sorted().forEach(candidates::add); }
            candidates.add(Path.of(System.getProperty("java.home")));
        }
        for(Path candidate:candidates) {
            Path home=normalizeJdkHome(candidate);
            if(isJdk25(home)) return home;
        }
        throw new IllegalArgumentException("JDK 25 required; supply --java-home or run setup-jvm with an existing Java 17+");
    }
    static Path normalizeJdkHome(Path candidate) {
        Path home=candidate.toAbsolutePath().normalize();
        if(Files.isRegularFile(home.resolve("release")))return home;
        Path bundleHome=home.resolve("Contents/Home");
        return Files.isRegularFile(bundleHome.resolve("release"))?bundleHome:home;
    }
    static boolean isJdk25(Path home)throws IOException {
        Path release=home.resolve("release");
        return Files.isRegularFile(release)&&Files.readString(release).lines().anyMatch(line->line.matches("JAVA_VERSION=\"25(?:[.\"+].*)?"));
    }
    private static String javaExecutable() throws IOException { return javaHome().resolve("bin").resolve(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java").toString(); }
    private static List<String> gradleCommand(List<String> tasks,boolean inProcess) throws Exception {
        var cmd=new ArrayList<String>(List.of(javaExecutable()));
        if(inProcess)cmd.addAll(List.of("-Dorg.gradle.jvmargs=", "-Dorg.gradle.internal.instrumentation.agent=false", "-Dorg.gradle.daemon=false"));
        else cmd.add("-Xmx2g");
        cmd.addAll(List.of("-classpath",repo.resolve("gradle/wrapper/gradle-wrapper.jar").toString(),"org.gradle.wrapper.GradleWrapperMain","--no-daemon"));
        if(inProcess)cmd.add("-Pkotlin.compiler.execution.strategy=in-process");cmd.addAll(tasks);return cmd;
    }
    private static void gradle(List<String> tasks) throws Exception { run(gradleCommand(tasks,inProcessGradle),true); }
    private static Map<String,Object> collectorPreparation(List<String> args) throws Exception {
        if(args.contains("--build-mode"))throw new IllegalArgumentException("Build backend is fixed to Gradle; omit --build-mode");
        var command=gradleCommand(List.of("stageRuntime"),true);
        return map("backend","gradle-stageRuntime","command",command,"environmentOverrides",commandEnvironment(true),"scope","Prepare current tool/main JARs before isolated bootstrap copy; collector performs its separate fresh audit build");
    }

    static void snapshotRuntime(Path libraries,Path bootstrap) throws IOException {
        if(!Files.isDirectory(libraries))throw new IllegalArgumentException("Current staged libraries missing after preparation");
        Files.createDirectories(bootstrap);
        try(var files=Files.list(libraries)){for(Path file:files.toList())if(Files.isRegularFile(file))Files.copy(file,bootstrap.resolve(file.getFileName()));}
    }
    private static void launch(String main,List<String> args) throws Exception { launch(main,args,main.equals(TOOLS+"JvmEvidenceCollector")); }
    private static void launch(String main,List<String> args,boolean collector) throws Exception {
        Path lib=repo.resolve("build/emulator/lib");
        if(collector){
            var preparation=collectorPreparation(args);
            Path bootstrap=repo.resolve("local/evidence-tools-bootstrap-"+UUID.randomUUID()+"/lib");
            if(dryRun){dryRunPreparation=preparation;launchFromLibraries(main,args,bootstrap);}
            else collectorPipeline(()->{
                @SuppressWarnings("unchecked") var command=(List<String>)preparation.get("command");run(command,true);
            },()->snapshotRuntime(lib,bootstrap),()->launchFromLibraries(main,args,bootstrap));
        }else{
            if(!Files.isDirectory(lib))throw new IllegalArgumentException("Missing staged libraries; run build first");
            launchFromLibraries(main,args,lib);
        }
    }
    private static void launchFromLibraries(String main,List<String> args,Path lib) throws Exception {
        var cmd=new ArrayList<String>(List.of(javaExecutable(),"--enable-native-access=ALL-UNNAMED","-classpath",lib.toString()+File.separator+"*",main));
        if(main.startsWith(TOOLS)){cmd.addAll(List.of("--repo",repo.toString()));for(String key:main.equals(TOOLS+"JvmEvidenceCollector")?List.of("--java-home","--java-temp-directory","--firmware","--native-library"):List.<String>of())if(options.containsKey(key))cmd.addAll(List.of(key,absolute(options.get(key)).toString()));}cmd.addAll(args);run(cmd,true);
    }
    private static Map<String,String> commandEnvironment(boolean jdk) throws Exception {
        var environment=new LinkedHashMap<String,String>();
        if(System.getenv("FUJI_TOOL_ENTRYPOINT")==null)environment.put("FUJI_TOOL_ENTRYPOINT","java tools/EmulatorTools.java "+currentCommand);
        if(System.getenv("FUJI_TOOL_SHELL")==null)environment.put("FUJI_TOOL_SHELL","none; direct JVM process");
        if(jdk)environment.put("JAVA_HOME",javaHome().toString());
        if(options.containsKey("--firmware"))environment.put("FUJI_FIRMWARE_FILE",absolute(options.get("--firmware")).toString());
        if(options.containsKey("--native-library"))environment.put("FUJI_DYNARMIC_LIBRARY",absolute(options.get("--native-library")).toString());
        if(options.containsKey("--gradle-home"))environment.put("GRADLE_USER_HOME",absolute(options.get("--gradle-home")).toString());
        if(options.containsKey("--java-temp-directory")){
            Path temp=absolute(options.get("--java-temp-directory"));if(!dryRun)Files.createDirectories(temp);
            environment.put("TEMP",temp.toString());environment.put("TMP",temp.toString());String old=System.getenv("JAVA_TOOL_OPTIONS");
            environment.put("JAVA_TOOL_OPTIONS",(old==null?"":old+" ")+"-Djava.io.tmpdir=\""+temp+"\"");
        }
        String inheritedOptions=environment.getOrDefault("JAVA_TOOL_OPTIONS",System.getenv("JAVA_TOOL_OPTIONS"));
        environment.put("JAVA_TOOL_OPTIONS",(inheritedOptions==null?"":inheritedOptions+" ")+"-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8");
        return environment;
    }
    private static void run(List<String> command,boolean jdk) throws Exception {
        var environment=commandEnvironment(jdk);
        if(dryRun){var plan=map("command",command,"environmentOverrides",environment);if(dryRunPreparation!=null)plan.put("preparation",dryRunPreparation);System.out.println(Json.write(plan));return;}
        var process=new ProcessBuilder(command).directory(repo.toFile()).inheritIO();process.environment().putAll(environment);
        checkProcessExit(process.start().waitFor());
    }
    private static Path absolute(String value) { Path p=Path.of(value); return (p.isAbsolute()?p:repo.resolve(p)).normalize(); }
    private static Map<String,Object> map(Object... pairs) { var result=new LinkedHashMap<String,Object>(); for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]); return result; }
    @SuppressWarnings("unchecked") private static Map<String,Object> obj(Object o) { if(!(o instanceof Map))throw new IllegalArgumentException("Expected object");return (Map<String,Object>)o; }
    @SuppressWarnings("unchecked") private static List<Object> array(Object o) { if(!(o instanceof List))throw new IllegalArgumentException("Expected array");return (List<Object>)o; }
    private static long number(Object o) { if(!(o instanceof Long))throw new IllegalArgumentException("Expected integer");return (Long)o; }
    private static void metadata(List<String> args) throws Exception {
        boolean check=args.equals(List.of("--check"));
        if(!args.isEmpty()&&!check)throw new IllegalArgumentException("metadata only accepts --check");
        gradle(List.of(check?":address-catalog:verifyCatalog":":address-catalog:compileCatalog"));
    }
    private static void httpCommand(List<String> args) throws Exception {
        if(args.size()!=2 && args.size()!=4)throw new IllegalArgumentException("http METHOD URL [--body-file PATH]");
        String method=args.get(0).toUpperCase(Locale.ROOT);URI uri=URI.create(args.get(1));
        if(!Set.of("GET","PATCH","POST").contains(method)||!"http".equals(uri.getScheme())||!Set.of("127.0.0.1","localhost","[::1]").contains(uri.getHost()))throw new IllegalArgumentException("HTTP controls require loopback HTTP and GET/PATCH/POST");
        String body="";if(args.size()==4){if(!args.get(2).equals("--body-file"))throw new IllegalArgumentException("Expected --body-file");Path file=absolute(args.get(3));if(Files.size(file)>65536)throw new IllegalArgumentException("HTTP body exceeds 64KiB");body=Files.readString(file);Json.parse(body);}
        if(dryRun){System.out.println(Json.write(map("method",method,"url",uri.toString(),"body",body)));return;}
        var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body)).build();
        var response=http().send(request,HttpResponse.BodyHandlers.ofString());System.out.println(response.body());if(response.statusCode()<200||response.statusCode()>=300)throw new IOException("HTTP control returned "+response.statusCode());
    }
    private static HttpClient http() { return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build(); }
    private static byte[] get(HttpClient client,URI uri) throws Exception { var response=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());if(response.statusCode()!=200)throw new IOException("HTTP "+response.statusCode()+" from "+uri.getHost());return response.body(); }
    private static void setup(List<String> args) throws Exception {
        String version="25",arch=System.getProperty("os.arch").matches("aarch64|arm64")?"aarch64":"x64";
        for(int i=0;i<args.size();i++){String flag=args.get(i);if(i+1==args.size())throw new IllegalArgumentException("Missing "+flag);String value=args.get(++i);if(flag.equals("--version"))version=value;else if(flag.equals("--architecture"))arch=value;else throw new IllegalArgumentException("Unknown setup flag "+flag);}
        if(!version.equals("25")||!Set.of("x64","aarch64").contains(arch))throw new IllegalArgumentException("Only JDK25 and x64/aarch64 are admitted");
        String name=System.getProperty("os.name").toLowerCase(Locale.ROOT),os=name.contains("win")?"windows":name.contains("mac")?"mac":name.contains("linux")?"linux":null;
        if(os==null)throw new IllegalArgumentException("Unsupported OS");URI api=URI.create("https://api.adoptium.net/v3/assets/latest/"+version+"/hotspot?architecture="+arch+"&image_type=jdk&os="+os+"&vendor=eclipse");
        if(dryRun){System.out.println(Json.write(map("api",api.toString(),"formats",List.of("zip","tar.gz"),"destination",".tools","requiresBootstrapJava",17L)));return;}
        var client=http();var releases=array(Json.parse(new String(get(client,api),StandardCharsets.UTF_8)));if(releases.isEmpty())throw new IOException("No matching JDK release");var pkg=obj(obj(obj(releases.get(0)).get("binary")).get("package"));
        String filename=(String)pkg.get("name"),checksum=(String)pkg.get("checksum");if(filename==null||!Path.of(filename).getFileName().toString().equals(filename)||!checksum.matches("[a-fA-F0-9]{64}"))throw new IOException("Invalid API package metadata");
        Path destination=repo.resolve(".tools");Files.createDirectories(destination);Path archive=destination.resolve(filename),partial=destination.resolve(filename+".partial");
        if(Files.exists(partial))throw new IOException("Preserved partial download already exists; inspect/remove it explicitly");
        var request=HttpRequest.newBuilder(URI.create((String)pkg.get("link"))).timeout(Duration.ofMinutes(10)).GET().build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());if(response.statusCode()!=200){response.body().close();throw new IOException("Archive HTTP "+response.statusCode());}
        try(var input=response.body();var out=Files.newOutputStream(partial,StandardOpenOption.CREATE_NEW)){long count=input.transferTo(out);if(count!=number(pkg.get("size")))throw new IOException("JDK archive size mismatch");}
        if(!sha(partial).equalsIgnoreCase(checksum))throw new IOException("JDK archive checksum mismatch (partial preserved)");Files.move(partial,archive,StandardCopyOption.REPLACE_EXISTING);
        Path extracted=destination.resolve("jdk-extract-"+System.nanoTime());Files.createDirectory(extracted);extract(archive,extracted);
        List<Path> roots;try(var entries=Files.list(extracted)){roots=entries.toList();}if(roots.size()!=1||!Files.isDirectory(roots.get(0)))throw new IOException("Unexpected JDK archive root");
        Path installed=destination.resolve(roots.get(0).getFileName());if(Files.exists(installed))throw new IOException("Existing JDK left untouched: "+installed);Files.move(roots.get(0),installed);Files.delete(extracted);
        Files.writeString(destination.resolve("jdk-download.json"),Json.write(map("name",filename,"link",pkg.get("link"),"checksum",checksum,"size",pkg.get("size"),"os",os,"architecture",arch,"api",api.toString()))+"\n");System.out.println("JDK installed; archive SHA-256 verified. Usable JAVA_HOME: "+normalizeJdkHome(installed));
    }
    private static String sha(Path file) throws Exception { var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(file)){var bytes=new byte[65536];for(int n;(n=in.read(bytes))>=0;)digest.update(bytes,0,n);}return HexFormat.of().formatHex(digest.digest()); }
    static Path safeEntry(Path root,String name) throws IOException {
        if(name.isEmpty()||name.indexOf('\\')>=0||name.indexOf(':')>=0||name.indexOf('\0')>=0)throw new IOException("Unsafe archive entry");
        Path entry=root.resolve(name).normalize();if(!entry.startsWith(root)||entry.equals(root)||Path.of(name).isAbsolute())throw new IOException("Archive path escapes destination");
        for(Path p=entry;p!=null&&p.startsWith(root);p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Archive entry traverses symlink");return entry;
    }
    static void extract(Path archive,Path destination) throws IOException {
        if(archive.toString().endsWith(".zip")){try(var in=new ZipInputStream(Files.newInputStream(archive))){for(ZipEntry e;(e=in.getNextEntry())!=null;){Path p=safeEntry(destination,e.getName());if(e.isDirectory())Files.createDirectories(p);else{Files.createDirectories(p.getParent());try(var out=Files.newOutputStream(p,StandardOpenOption.CREATE_NEW)){in.transferTo(out);}}}}return;}
        if(!archive.toString().endsWith(".tar.gz"))throw new IOException("Unsupported archive format");
        try(var in=new GZIPInputStream(Files.newInputStream(archive))){byte[] header=new byte[512];String longName=null;Map<String,String> pax=new LinkedHashMap<>();var links=new ArrayList<String[]>();
            while(true){int n=in.readNBytes(header,0,512);if(n==0)break;if(n!=512)throw new IOException("Truncated tar header");boolean zero=true;for(byte b:header)if(b!=0){zero=false;break;}if(zero)break;
                long stored=octal(header,148,8),sum=0;for(int i=0;i<512;i++)sum+=(i>=148&&i<156)?32:Byte.toUnsignedInt(header[i]);if(stored!=sum)throw new IOException("Tar header checksum mismatch");
                long size=octal(header,124,12);char type=(char)header[156];String name=cString(header,0,100),prefix=cString(header,345,155);if(!prefix.isEmpty())name=prefix+"/"+name;
                if(type=='x'||type=='g'||type=='L'){if(size>1048576)throw new IOException("Oversize tar metadata");byte[] data=in.readNBytes((int)size);if(data.length!=size)throw new EOFException();if(type=='L')longName=new String(data,StandardCharsets.UTF_8).replace("\0","").stripTrailing();else {if(type=='g')throw new IOException("Global PAX metadata unsupported");pax=pax(data);}skipFully(in,(512-size%512)%512);continue;}
                if(longName!=null){name=longName;longName=null;}if(pax.containsKey("path"))name=pax.get("path");String link=pax.getOrDefault("linkpath",cString(header,157,100));pax.clear();Path p=safeEntry(destination,name);
                if(type=='5')Files.createDirectories(p);else if(type=='0'||type==0){Files.createDirectories(p.getParent());try(var out=Files.newOutputStream(p,StandardOpenOption.CREATE_NEW)){copyExactly(in,out,size);}try{Files.setPosixFilePermissions(p,java.nio.file.attribute.PosixFilePermissions.fromString((octal(header,100,8)&0111)!=0?"rwxr-xr-x":"rw-r--r--"));}catch(UnsupportedOperationException ignored){}}
                else if(type=='2'||type=='1'){if(size!=0)throw new IOException("Unexpected link data");links.add(new String[]{name,link,String.valueOf(type)});}else throw new IOException("Unsupported tar entry type "+type);
                if(type!='0'&&type!=0&&size!=0)throw new IOException("Unexpected tar payload");skipFully(in,(512-size%512)%512);
            }
            for(String[] link:links){Path p=safeEntry(destination,link[0]);Path target=(link[2].equals("2")?p.getParent():destination).resolve(link[1]).normalize();if(Path.of(link[1]).isAbsolute()||!target.startsWith(destination)||link[1].indexOf('\\')>=0||link[1].indexOf(':')>=0)throw new IOException("Unsafe tar link");Files.createDirectories(p.getParent());if(link[2].equals("2"))Files.createSymbolicLink(p,p.getParent().relativize(target));else Files.createLink(p,safeEntry(destination,destination.relativize(target).toString().replace('\\','/')));}
        }
    }
    private static Map<String,String> pax(byte[] data)throws IOException {var out=new LinkedHashMap<String,String>();int at=0;while(at<data.length){int space=at;while(space<data.length&&data[space]!=' ')space++;if(space==data.length)throw new IOException("Malformed PAX record");int count;try{count=Integer.parseInt(new String(data,at,space-at,StandardCharsets.US_ASCII));}catch(NumberFormatException e){throw new IOException("Malformed PAX length");}if(count<=space-at+1||at+count>data.length||data[at+count-1]!='\n')throw new IOException("Malformed PAX record length");String line=new String(data,space+1,at+count-space-2,StandardCharsets.UTF_8);int eq=line.indexOf('=');if(eq<1)throw new IOException("Malformed PAX key");out.put(line.substring(0,eq),line.substring(eq+1));at+=count;}return out;}
    private static String cString(byte[] a,int start,int length) {int end=start;while(end<start+length&&a[end]!=0)end++;return new String(a,start,end-start,StandardCharsets.UTF_8);}
    private static long octal(byte[] a,int start,int length)throws IOException{String value=cString(a,start,length).trim();try{return value.isEmpty()?0:Long.parseLong(value,8);}catch(NumberFormatException e){throw new IOException("Invalid tar numeric field");}}
    private static void copyExactly(InputStream in,OutputStream out,long size)throws IOException {byte[] b=new byte[65536];while(size>0){int n=in.read(b,0,(int)Math.min(b.length,size));if(n<0)throw new EOFException();out.write(b,0,n);size-=n;}}
    private static void skipFully(InputStream in,long size)throws IOException {while(size>0){long n=in.skip(size);if(n==0){if(in.read()<0)throw new EOFException();n=1;}size-=n;}}
    private static void selfTest()throws Exception {
        int checks=0; Object sample=Json.parse("{\"a\":[1,true,null,\"汉字\\n\"],\"b\":-2}");if(!sample.equals(Json.parse(Json.write(sample))))throw new AssertionError("JSON roundtrip");checks++;
        for(String invalid:List.of("{\"x\":null,\"x\":2}","[01]","[1.]","[1e]","1 trailing","[1,]","\"bad\nline\"")){try{Json.parse(invalid);throw new AssertionError("Accepted invalid JSON");}catch(IllegalArgumentException expected){checks++;}}
        Path root=Files.createTempDirectory("fuji-tools-");try {
            Path directHome=root.resolve("direct-home");Files.createDirectories(directHome);Files.writeString(directHome.resolve("release"),"JAVA_VERSION=\"25.0.1\"\n");if(!normalizeJdkHome(directHome).equals(directHome)||!isJdk25(normalizeJdkHome(directHome)))throw new AssertionError("Direct JDK home");checks++;
            Path bundle=root.resolve("mac-bundle.jdk"),bundleHome=bundle.resolve("Contents/Home");Files.createDirectories(bundleHome);Files.writeString(bundleHome.resolve("release"),"JAVA_VERSION=\"25.0.1\"\n");if(!normalizeJdkHome(bundle).equals(bundleHome)||!isJdk25(normalizeJdkHome(bundle)))throw new AssertionError("Mac bundle JDK home shape");checks++;
            if(!normalizeJdkHome(bundleHome).equals(bundleHome)||!isJdk25(bundleHome))throw new AssertionError("Already normalized bundle home");checks++;
            Path wrong=root.resolve("wrong-home");Files.createDirectories(wrong);Files.writeString(wrong.resolve("release"),"JAVA_VERSION=\"17.0.1\"\n");if(isJdk25(normalizeJdkHome(wrong)))throw new AssertionError("Non25 release accepted");checks++;
            if(isJdk25(normalizeJdkHome(root.resolve("missing-home"))))throw new AssertionError("Missing release accepted");checks++;
            for(String invalid:List.of("../escape","/absolute","C:/escape","dir\\file")){try{safeEntry(root,invalid);throw new AssertionError("Accepted unsafe path");}catch(IOException expected){checks++;}}
            Path zip=root.resolve("fixture.zip");try(var out=new ZipOutputStream(Files.newOutputStream(zip))){out.putNextEntry(new ZipEntry("jdk-test/bin/java"));out.write("fixture".getBytes(StandardCharsets.UTF_8));out.closeEntry();}Path extracted=root.resolve("extract");Files.createDirectory(extracted);extract(zip,extracted);if(!Files.readString(extracted.resolve("jdk-test/bin/java")).equals("fixture"))throw new AssertionError("ZIP extract");checks++;
            Path evil=root.resolve("evil.zip");try(var out=new ZipOutputStream(Files.newOutputStream(evil))){out.putNextEntry(new ZipEntry("../escape"));out.write(1);out.closeEntry();}try{extract(evil,extracted);throw new AssertionError("ZIP escape");}catch(IOException expected){checks++;}
            Path tar=root.resolve("fixture.tar.gz");byte[] header=new byte[512];tarField(header,0,100,"jdk-test/bin/java");tarField(header,100,8,"0000755");tarField(header,124,12,"00000000007");Arrays.fill(header,148,156,(byte)' ');header[156]='0';long sum=0;for(byte b:header)sum+=Byte.toUnsignedInt(b);tarField(header,148,8,String.format("%06o",sum));try(var out=new GZIPOutputStream(Files.newOutputStream(tar))){out.write(header);out.write("fixture".getBytes(StandardCharsets.UTF_8));out.write(new byte[505+1024]);}Path tarRoot=root.resolve("tar-extract");Files.createDirectory(tarRoot);extract(tar,tarRoot);if(!Files.readString(tarRoot.resolve("jdk-test/bin/java")).equals("fixture"))throw new AssertionError("TAR extract");checks++;
            header[0]^=1;Path bad=root.resolve("bad.tar.gz");try(var out=new GZIPOutputStream(Files.newOutputStream(bad))){out.write(header);}try{extract(bad,tarRoot);throw new AssertionError("Tar checksum");}catch(IOException expected){checks++;}

            for(String[] item:new String[][]{{"../escape","0",""},{"jdk-test/link","2","../../../escape"}}){Path unsafe=root.resolve("unsafe-"+checks+".tar.gz");try(var out=new GZIPOutputStream(Files.newOutputStream(unsafe))){tarEntry(out,item[0],item[1].charAt(0),item[2],new byte[0]);out.write(new byte[1024]);}try{extract(unsafe,root.resolve("unsafe-root-"+checks));throw new AssertionError("Unsafe TAR accepted");}catch(IOException expected){checks++;}}
            Path longTar=root.resolve("long.tar.gz");String longPath="jdk-test/"+"a".repeat(110)+"/java";try(var out=new GZIPOutputStream(Files.newOutputStream(longTar))){tarEntry(out,"././@LongLink",'L',"",(longPath+"\0").getBytes(StandardCharsets.UTF_8));tarEntry(out,"placeholder",'0',"","fixture".getBytes(StandardCharsets.UTF_8));out.write(new byte[1024]);}Path longRoot=root.resolve("long-root");Files.createDirectory(longRoot);extract(longTar,longRoot);if(!Files.readString(longRoot.resolve(longPath)).equals("fixture"))throw new AssertionError("GNU long name");checks++;
            Path paxTar=root.resolve("pax.tar.gz");String payload="path=jdk-test/pax/java\n";int length=payload.length()+2;while((length+" "+payload).getBytes(StandardCharsets.UTF_8).length!=length)length=(length+" "+payload).getBytes(StandardCharsets.UTF_8).length;try(var out=new GZIPOutputStream(Files.newOutputStream(paxTar))){tarEntry(out,"pax",'x',"",(length+" "+payload).getBytes(StandardCharsets.UTF_8));tarEntry(out,"placeholder",'0',"","fixture".getBytes(StandardCharsets.UTF_8));out.write(new byte[1024]);}Path paxRoot=root.resolve("pax-root");Files.createDirectory(paxRoot);extract(paxTar,paxRoot);if(!Files.readString(paxRoot.resolve("jdk-test/pax/java")).equals("fixture"))throw new AssertionError("PAX path");checks++;
            try{extract(root.resolve("unsupported.7z"),root);throw new AssertionError("Unknown archive type");}catch(IOException expected){checks++;}
        }finally{try(var entries=Files.walk(root)){for(Path p:entries.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
        System.out.println(Json.write(map("profile","project-tools-self-test-v1","checks",(long)checks,"passed",true,"shell",false,"setupNetworkDownloaded",false)));
    }
    private static void tarEntry(OutputStream out,String name,char type,String link,byte[] data)throws IOException{byte[] header=new byte[512];tarField(header,0,100,name);tarField(header,100,8,"0000755");tarField(header,124,12,String.format("%011o",data.length));Arrays.fill(header,148,156,(byte)' ');header[156]=(byte)type;tarField(header,157,100,link);long sum=0;for(byte b:header)sum+=Byte.toUnsignedInt(b);tarField(header,148,8,String.format("%06o",sum));out.write(header);out.write(data);out.write(new byte[(512-data.length%512)%512]);}
    private static void tarField(byte[] h,int start,int length,String value){byte[] b=value.getBytes(StandardCharsets.US_ASCII);System.arraycopy(b,0,h,start,Math.min(b.length,length));}
    /** Strict bounded JSON support for public catalog/tool metadata; no external bootstrap dependency. */
    static final class Json {
        private final String s;private int p;private int depth;
        private Json(String s){if(s.length()>32*1024*1024)throw new IllegalArgumentException("JSON exceeds tool limit");this.s=s.startsWith("\ufeff")?s.substring(1):s;}
        static Object parse(String s){var j=new Json(s);Object v=j.value();j.ws();if(j.p!=j.s.length())throw j.bad();return v;}
        private IllegalArgumentException bad(){return new IllegalArgumentException("Invalid JSON at offset "+p);}
        private void ws(){while(p<s.length()&&" \t\r\n".indexOf(s.charAt(p))>=0)p++;}
        private Object value(){ws();if(++depth>128)throw bad();try{if(p==s.length())throw bad();char c=s.charAt(p);if(c=='{'){p++;var m=new LinkedHashMap<String,Object>();ws();if(take('}'))return m;while(true){ws();if(p==s.length()||s.charAt(p)!='"')throw bad();String k=string();ws();if(!take(':'))throw bad();Object v=value();if(m.containsKey(k))throw bad();m.put(k,v);ws();if(take('}'))return m;if(!take(','))throw bad();}}if(c=='['){p++;var a=new ArrayList<Object>();ws();if(take(']'))return a;while(true){a.add(value());ws();if(take(']'))return a;if(!take(','))throw bad();}}if(c=='"')return string();if(s.startsWith("true",p)){p+=4;return true;}if(s.startsWith("false",p)){p+=5;return false;}if(s.startsWith("null",p)){p+=4;return null;}int start=p;if(take('-')&&p==s.length())throw bad();if(take('0')){if(p<s.length()&&Character.isDigit(s.charAt(p)))throw bad();}else{if(p==s.length()||s.charAt(p)<'1'||s.charAt(p)>'9')throw bad();while(p<s.length()&&s.charAt(p)>='0'&&s.charAt(p)<='9')p++;}if(p<s.length()&&".eE".indexOf(s.charAt(p))>=0){while(p<s.length()&&"0123456789.eE+-".indexOf(s.charAt(p))>=0)p++;try{String numeric=s.substring(start,p);if(!numeric.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw bad();return new java.math.BigDecimal(numeric);}catch(NumberFormatException e){throw bad();}}try{return Long.parseLong(s.substring(start,p));}catch(NumberFormatException e){throw bad();}}finally{depth--;}}
        private boolean take(char c){if(p<s.length()&&s.charAt(p)==c){p++;return true;}return false;}
        private String string(){if(!take('"'))throw bad();var out=new StringBuilder();while(p<s.length()){char c=s.charAt(p++);if(c=='"')return out.toString();if(c<32)throw bad();if(c=='\\'){if(p==s.length())throw bad();c=s.charAt(p++);switch(c){case '"','\\','/'->out.append(c);case 'b'->out.append('\b');case 'f'->out.append('\f');case 'n'->out.append('\n');case 'r'->out.append('\r');case 't'->out.append('\t');case 'u'->{if(p+4>s.length())throw bad();try{out.append((char)Integer.parseInt(s.substring(p,p+4),16));p+=4;}catch(NumberFormatException e){throw bad();}}default->throw bad();}}else out.append(c);}throw bad();}
        static String write(Object value){if(value==null)return "null";if(value instanceof String s){var b=new StringBuilder("\"");for(char c:s.toCharArray()){switch(c){case '"'->b.append("\\\"");case '\\'->b.append("\\\\");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");default->{if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}}return b.append('"').toString();}if(value instanceof Map<?,?> m){var a=new ArrayList<String>();m.forEach((k,v)->a.add(write(k)+":"+write(v)));return "{"+String.join(",",a)+"}";}if(value instanceof Collection<?> c)return "["+String.join(",",c.stream().map(Json::write).toList())+"]";if(value instanceof Number||value instanceof Boolean)return value.toString();throw new IllegalArgumentException("Unsupported JSON value");}
    }
}
