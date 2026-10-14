package vn.cuuvuc.validation.baseline;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import vn.cuuvuc.core.ContractWorld;
import vn.cuuvuc.core.Contracts;
import vn.cuuvuc.core.GameDatabase;
import vn.cuuvuc.validation.measure.MeasureHook;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** Connector workload Paper với player online thật; không tự phát packet hoặc event thi công. */
public final class BaselineProbe extends JavaPlugin implements Listener {
    private static final UUID OWNER=UUID.fromString("f3d83b55-4452-39bc-85f4-29f8cebdcc4f");
    private static final UUID BUILDER=UUID.fromString("3bb5ddbc-5b75-377f-8647-6f25c51f252a");
    private static final UUID ORIGINAL=UUID.fromString("279bbdf9-0fe9-4eec-8bb6-46a9df8c72a3");
    private final AtomicBoolean busy=new AtomicBoolean();
    private final AtomicBoolean delayTick=new AtomicBoolean();
    private GameDatabase database;
    private ContractWorld contracts;
    private World world;
    private long tickStart;
    private UUID current;
    private int dirtySequence;

    @Override public void onEnable() {
        if(!Path.of("").toAbsolutePath().getFileName().toString().startsWith("run-b02-"))throw new IllegalStateException("Probe chỉ cho run-b02-*");
        JavaPlugin core=(JavaPlugin)Bukkit.getPluginManager().getPlugin("CuuVucCore");if(core==null)throw new IllegalStateException("Thiếu core");
        try{database=(GameDatabase)field(core,"database");contracts=(ContractWorld)field(core,"contracts");}
        catch(ReflectiveOperationException failure){throw new IllegalStateException("Không đọc được connector core",failure);}
        world=java.util.Objects.requireNonNull(Bukkit.getWorld("cv_sky"));
        getServer().getPluginManager().registerEvents(this,this);
        Bukkit.getAsyncScheduler().runAtFixedRate(this,task->poll(),1,100,TimeUnit.MILLISECONDS);
    }
    private static Object field(JavaPlugin core,String name) throws ReflectiveOperationException {Field value=core.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(core);}
    @EventHandler(priority=EventPriority.LOWEST) public void tickStart(ServerTickStartEvent event) {
        tickStart=System.nanoTime();
        if(delayTick.compareAndSet(true,false)){
            long start=System.nanoTime();try{Thread.sleep(2100);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException("Calibration tick bị ngắt",failure);}
            MeasureHook.marker("CAL_TICK",System.nanoTime()-start,"tick="+event.getTickNumber());
        }
    }
    @EventHandler(priority=EventPriority.MONITOR) public void tickEnd(ServerTickEndEvent event){MeasureHook.apiTick(event.getTickNumber(),event.getTickDuration(),tickStart);}
    private void poll() {
        Path path=Path.of("benchmark","command.txt");if(!Files.exists(path)||!busy.compareAndSet(false,true))return;
        String command="unknown";
        try {
            String[] parts=Files.readString(path,StandardCharsets.UTF_8).strip().split(" ");
            if(parts.length!=2||!parts[0].matches("[a-z0-9-]+"))throw new IllegalArgumentException("Cần id action");
            command=parts[0];Files.move(path,path.resolveSibling(command+".claimed"),StandardCopyOption.ATOMIC_MOVE);
            switch(parts[1]){
                case "PREPARE"->prepare();
                case "CALIBRATE"->calibrate();
                case "RUN"->runWindows();
                case "STOP"->Bukkit.getGlobalRegionScheduler().execute(this,Bukkit::shutdown);
                default->throw new IllegalArgumentException("Action không hợp lệ: "+parts[1]);
            }
            MeasureHook.flush();Files.writeString(Path.of("benchmark",command+".done"),"DONE "+command,StandardCharsets.UTF_8);
        }catch(IOException|SQLException|InterruptedException|ExecutionException|TimeoutException|IllegalArgumentException|IllegalStateException failure){
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            getSLF4JLogger().atError().addKeyValue("command",command).setCause(failure).log("B02_MEASUREMENT_FAILED");
            try{Files.writeString(Path.of("benchmark","failed.txt"),command+" "+failure,StandardCharsets.UTF_8);}catch(IOException fileFailure){throw new IllegalStateException("Không ghi được lỗi probe",fileFailure);}
        }finally{busy.set(false);}
    }
    private Player player(UUID id){Player value=Bukkit.getPlayer(id);if(value==null)throw new IllegalStateException("Cần Client online: "+id);return value;}
    private Contracts.Job job(UUID id) throws SQLException {return database.contractBoard().jobs().stream().filter(value->value.id().equals(id)).findFirst().orElseThrow();}
    private void region(Runnable action) throws InterruptedException,ExecutionException,TimeoutException {
        CompletableFuture<Void> result=new CompletableFuture<>();
        Bukkit.getRegionScheduler().execute(this,new Location(world,72,100,8),()->{action.run();result.complete(null);});result.get(10,TimeUnit.SECONDS);
    }
    private void prepare() throws SQLException,InterruptedException,ExecutionException,TimeoutException {
        player(OWNER);player(BUILDER);MeasureHook.phase("fixture-prepare");
        region(()->{world.setChunkForceLoaded(4,0,true);world.setChunkForceLoaded(4,1,true);for(int x=71;x<=73;x++)for(int z=7;z<=9;z++)world.getBlockAt(x,100,z).setType(Material.COBBLESTONE,false);});
        // Kết thúc hợp đồng nguồn bằng API thật, ngoài khoảng đo; giữ đối ứng tài sản của backup.
        Contracts.Job original=job(ORIGINAL);
        if(original.status()!=Contracts.Status.ACTIVE)throw new IllegalStateException("Backup nguồn phải ACTIVE");
        database.submitContract(ORIGINAL,BUILDER);database.completeContract(ORIGINAL,OWNER);
        newContract();
        MeasureHook.marker("FIXTURE_READY",0,"current="+current+" api_only_original_settlement=true clients=2 template_console=true");
    }
    private void newContract() throws SQLException,InterruptedException,ExecutionException,TimeoutException {
        database.pay(UUID.randomUUID(),BUILDER,"TyninWorld",OWNER,2);
        current=UUID.randomUUID();database.createContract(current,OWNER,2,Duration.ofHours(1));database.acceptContract(current,BUILDER);
        contracts.publish(database.contractBoard());
    }
    private void request(UUID actorId,Contracts.Status expected,String action,BiConsumer<Player,Contracts.Job> dispatch) throws SQLException,InterruptedException,TimeoutException,ExecutionException {
        if(MeasureHook.inFlight()!=0)throw new IllegalStateException("Lượt đơn có request khác đang xử lý");
        Contracts.Job value=job(current);UUID request=Contracts.request(current,action);
        Player actor=player(actorId);
        CompletableFuture<Void> dispatched=new CompletableFuture<>();
        actor.getScheduler().run(this,task->{dispatch.accept(actor,value);dispatched.complete(null);},()->dispatched.completeExceptionally(new IllegalStateException("Client offline khi dispatch")));
        dispatched.get(10,TimeUnit.SECONDS);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!MeasureHook.responded(request)&&System.nanoTime()<deadline)Thread.sleep(5);
        if(!MeasureHook.responded(request))throw new IllegalStateException("Thiếu phản hồi request="+request+" status="+job(current).status()+" inflight="+MeasureHook.inFlight());
        String response=MeasureHook.response(request);
        if(job(current).status()!=expected)throw new IllegalStateException("Request không đạt trạng thái: "+request+" response="+response);
    }
    private void calibrate() throws InterruptedException,ExecutionException,TimeoutException {
        MeasureHook.phase("calibration");delayTick.set(true);Thread.sleep(2800);
        for(int index=0;index<5;index++){
            CompletableFuture<Void> result=new CompletableFuture<>();
            Bukkit.getGlobalRegionScheduler().execute(this,()->{long start=System.nanoTime();world.save();MeasureHook.marker("CAL_SAVE_OUTER",System.nanoTime()-start,"call="+System.nanoTime());result.complete(null);});result.get(15,TimeUnit.SECONDS);
        }
    }
    private void runWindows() throws IOException,SQLException,InterruptedException,ExecutionException,TimeoutException {
        Properties configuration=new Properties();try(InputStream input=Files.newInputStream(Path.of("benchmark","workload.properties"))){configuration.load(input);}
        int repeats=Integer.parseInt(java.util.Objects.requireNonNull(configuration.getProperty("repeats")));
        int warmup=Integer.parseInt(java.util.Objects.requireNonNull(configuration.getProperty("warmup_seconds")));
        int measurement=Integer.parseInt(java.util.Objects.requireNonNull(configuration.getProperty("measurement_seconds")));
        int period=Integer.parseInt(java.util.Objects.requireNonNull(configuration.getProperty("cycle_period_ms")));
        if(repeats<1||warmup<1||measurement<1||period<100)throw new IllegalArgumentException("Workload config không hợp lệ");
        for(int repeat=1;repeat<=repeats;repeat++)for(String workload:List.of("idle","clean","dirty")){
            window(workload+"-r"+repeat+"-warmup",workload,warmup,period);
            window(workload+"-r"+repeat+"-measure",workload,measurement,period);
            MeasureHook.flush();Files.writeString(Path.of("benchmark","progress.txt"),workload+" r="+repeat+" complete",StandardCharsets.UTF_8);
        }
        MeasureHook.phase("finished");MeasureHook.marker("RUN_COMPLETE",0,"inflight="+MeasureHook.inFlight());
    }
    private void window(String label,String workload,int seconds,int period) throws SQLException,InterruptedException,ExecutionException,TimeoutException {
        if(MeasureHook.inFlight()!=0)throw new IllegalStateException("Request vượt ranh giới cửa sổ");
        MeasureHook.phase(label);long start=System.nanoTime();MeasureHook.marker("WINDOW_START",0,"offered_cycle_ms="+period+" active_rows="+database.contractBoard().jobs().stream().filter(value->value.status()==Contracts.Status.ACTIVE).count());
        long deadline=start+TimeUnit.SECONDS.toNanos(seconds);
        while(System.nanoTime()<deadline){
            if(workload.equals("idle")){Thread.sleep(100);continue;}
            long cycle=System.nanoTime();
            if(workload.equals("dirty"))markDirty();
            request(BUILDER,Contracts.Status.SUBMITTED,"SUBMIT",contracts::submit);
            if(workload.equals("dirty"))markDirty();
            request(OWNER,Contracts.Status.COMPLETED,"COMPLETE",contracts::complete);newContract();
            long left=TimeUnit.MILLISECONDS.toNanos(period)-(System.nanoTime()-cycle);if(left>0)TimeUnit.NANOSECONDS.sleep(left);
        }
        if(MeasureHook.inFlight()!=0)throw new IllegalStateException("Request chưa xong lúc hết cửa sổ");
        MeasureHook.marker("WINDOW_END",System.nanoTime()-start,"deadline_ns="+deadline);
    }
    private void markDirty() throws InterruptedException,ExecutionException,TimeoutException {
        region(()->{dirtySequence++;world.getBlockAt(64,95,0).setType(dirtySequence%2==0?Material.DIRT:Material.STONE,false);});
    }
    @Override public void onDisable(){try{MeasureHook.flush();}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException("Không flush được recorder",failure);}}
}
