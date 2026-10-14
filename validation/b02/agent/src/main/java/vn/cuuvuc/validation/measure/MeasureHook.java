package vn.cuuvuc.validation.measure;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Connector bootstrap: ghi dữ liệu thô qua queue giới hạn, không ghi đĩa trên tick thread. */
public final class MeasureHook {
    private record Row(String text, CountDownLatch acknowledgement) { }
    public record Request(UUID id, UUID contract, UUID actor, String action, long start) { }
    private record Save(UUID request, long start) { }
    private static final LinkedBlockingQueue<Row> ROWS=new LinkedBlockingQueue<>(100000);
    private static final ConcurrentHashMap<UUID,Request> ACTIVE=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID,String> RESPONSES=new ConcurrentHashMap<>();
    private static final ThreadLocal<Save> SAVE=new ThreadLocal<>();
    private static final ThreadLocal<Integer> TICK_ID=new ThreadLocal<>();
    private static final AtomicInteger SAVES=new AtomicInteger();
    private static final AtomicLong ENQUEUED=new AtomicLong();
    private static final AtomicLong WRITTEN=new AtomicLong();
    private static volatile String phase="startup";
    private static volatile IOException failure;
    private static volatile boolean initialized;

    public static synchronized void initialize() throws IOException {
        if(initialized) throw new IllegalStateException("Recorder đã khởi tạo");
        Path directory=Path.of("benchmark"); Files.createDirectories(directory);
        BufferedWriter writer=Files.newBufferedWriter(directory.resolve("raw.tsv"),StandardCharsets.UTF_8);
        writer.write("type\tns\tduration_ns\tid\tcontract\tactor\taction\tinflight\tsaves\tphase\tdetail\n"); writer.flush();
        Thread thread=new Thread(()->writeRows(writer),"b02-record-writer"); thread.setDaemon(true); thread.start(); initialized=true;
        event("ENV",System.nanoTime(),0,"","","","",Instant.now()+" pid="+ProcessHandle.current().pid()+" mode="+System.getProperty("b02.mode"));
    }

    private static void writeRows(BufferedWriter writer) {
        try {
            while(true) {
                Row row=ROWS.poll(250,TimeUnit.MILLISECONDS);
                if(row==null){writer.flush();continue;}
                writer.write(row.text()); writer.newLine(); if(row.acknowledgement()==null)WRITTEN.incrementAndGet();
                if(row.acknowledgement()!=null){writer.flush();row.acknowledgement().countDown();}
            }
        } catch(IOException problem){failure=problem;}
        catch(InterruptedException problem){Thread.currentThread().interrupt();failure=new IOException("Writer bị ngắt",problem);}
    }

    private static String clean(String value){return value.replace("\t","\\t").replace("\r","\\r").replace("\n","\\n");}
    private static void event(String type,long now,long duration,String id,String contract,String actor,String action,String detail) {
        if(failure!=null) throw new IllegalStateException("Recorder lỗi I/O",failure);
        String row=type+"\t"+now+"\t"+duration+"\t"+id+"\t"+contract+"\t"+actor+"\t"+action+"\t"+ACTIVE.size()+"\t"+SAVES.get()+"\t"+phase+"\t"+clean(detail);
        if(!ROWS.offer(new Row(row,null))) throw new IllegalStateException("Queue đo đầy; hủy đánh giá phiên");
        ENQUEUED.incrementAndGet();
    }
    private static void overhead(String method,long start){event("OVERHEAD",System.nanoTime(),System.nanoTime()-start,"","","",method,"");}

    public static void phase(String value){phase=value;event("PHASE",System.nanoTime(),0,"","","","",value);}
    public static void marker(String kind,long duration,String detail){event(kind,System.nanoTime(),duration,"","","","",detail);}
    public static int inFlight(){return ACTIVE.size();}
    public static boolean responded(UUID request){return RESPONSES.containsKey(request);}
    public static String response(UUID request){String result=RESPONSES.remove(request);if(result==null)throw new IllegalStateException("Request chưa có phản hồi: "+request);return result;}

    public static void accepted(Object previous,Object player,Object job,UUID request) {
        if(previous!=null)return;
        long start=System.nanoTime();
        try {
            UUID contract=(UUID)job.getClass().getMethod("id").invoke(job);
            UUID actor=(UUID)player.getClass().getMethod("getUniqueId").invoke(player);
            UUID submit=UUID.nameUUIDFromBytes(("cuuvuc:contract:"+contract+":SUBMIT").getBytes(StandardCharsets.UTF_8));
            String action=request.equals(submit)?"SUBMIT":"APPROVE";
            Request value=new Request(request,contract,actor,action,start);
            if(ACTIVE.putIfAbsent(request,value)!=null)throw new IllegalStateException("Request đang xử lý bị lặp: "+request);
            event("REQUEST_START",start,0,request.toString(),contract.toString(),actor.toString(),action,"accepted_contract_fence");
        }catch(ReflectiveOperationException problem){throw new IllegalStateException("Không đọc được actor/contract của request "+request,problem);}
        overhead("accept",start);
    }
    public static void saveBegin(UUID request) {
        long start=System.nanoTime();
        if(SAVE.get()!=null)throw new IllegalStateException("Save lồng nhau không được nhận dạng: "+request);
        SAVES.incrementAndGet(); SAVE.set(new Save(request,System.nanoTime())); overhead("saveBegin",start);
    }
    public static void saveEnd(UUID request) {
        long end=System.nanoTime(); Save value=SAVE.get();
        if(value==null || !value.request().equals(request))throw new IllegalStateException("Mất cặp save: "+request);
        Request entry=ACTIVE.get(request);if(entry==null)throw new IllegalStateException("Save ngoài request đã nhận: "+request);
        event("SAVE",end,end-value.start(),request.toString(),entry.contract().toString(),entry.actor().toString(),entry.action(),"start="+value.start());
        SAVE.remove(); SAVES.decrementAndGet(); overhead("saveEnd",end);
    }
    public static void delegate(long start,long end,boolean flush) {
        Save context=SAVE.get();
        event("SAVE_DELEGATE",end,end-start,context==null?"":context.request().toString(),"","","", "flush="+flush+" start="+start);
    }
    public static void tick(long start,long end){Integer id=TICK_ID.get();event("TICK_METHOD",end,end-start,id==null?"":id.toString(),"","","","start="+start);}
    public static void apiTick(int tick,double milliseconds,long observedStart) {
        long start=System.nanoTime();
        TICK_ID.set(tick);
        event("TICK_API",start,(long)(milliseconds*1000000),Integer.toString(tick),"","","","observed_start="+observedStart+" observed_duration="+(start-observedStart));
        overhead("tickApi",start);
    }
    public static void databaseReturn(UUID contract,String action) {
        long start=System.nanoTime();
        for(Request value:ACTIVE.values())if(value.contract().equals(contract)&&value.action().equals(action)) {
            event("DB_RETURN",start,0,value.id().toString(),contract.toString(),value.actor().toString(),action,"transaction_returned_after_commit");
        }
        overhead("dbReturn",start);
    }
    public static void replied(UUID request,String message) {
        long end=System.nanoTime(); Request value=ACTIVE.get(request);if(value==null)return;
        event("RESPONSE",end,end-value.start(),request.toString(),value.contract().toString(),value.actor().toString(),value.action(),message);
        ACTIVE.remove(request); RESPONSES.put(request,message); event("REQUEST_END",System.nanoTime(),0,request.toString(),value.contract().toString(),value.actor().toString(),value.action(),"server_entity_reply_not_network_ack");
        overhead("reply",end);
    }
    public static void flush() throws InterruptedException {
        CountDownLatch acknowledgement=new CountDownLatch(1);
        String row="FLUSH\t"+System.nanoTime()+"\t0\t\t\t\t\t"+ACTIVE.size()+"\t"+SAVES.get()+"\t"+phase+"\tqueued="+ENQUEUED.get()+" written="+WRITTEN.get();
        if(!ROWS.offer(new Row(row,acknowledgement)))throw new IllegalStateException("Không xếp được flush recorder");
        if(!acknowledgement.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Recorder không flush được; không dùng dữ liệu",failure);
    }
}
