package com.example.facetofacetranslator;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import okio.ByteString;

/** Call state is serialized on main; incoming recognition never uses the microphone. */
public final class ListeningService extends Service {
    static final String ACTION_START="com.example.facetofacetranslator.START", ACTION_STOP="com.example.facetofacetranslator.STOP",
        ACTION_SEND="com.example.facetofacetranslator.SEND", ACTION_VOICE="com.example.facetofacetranslator.VOICE",
        ACTION_UPDATE="com.example.facetofacetranslator.UPDATE", ACTION_PLAY_PRERECORDED="com.example.facetofacetranslator.PLAY_PRERECORDED",
        EXTRA_TEXT="text", EXTRA_PRERECORDED_ID="prerecordedId";
    private static final Object LOCK=new Object();
    private static final List<String> HISTORY=new ArrayList<>();
    private static String status="尚未拨号", localText="";
    private static boolean active,busy,connected,voice;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor incoming=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(20));
    private final ExecutorService outgoing=Executors.newSingleThreadExecutor(), network=Executors.newSingleThreadExecutor();
    private final ArrayDeque<Pending> queued=new ArrayDeque<>();
    private GatewayClient gateway;
    private ParaformerRecognizer remote,microphone;
    private TextToSpeech tts;
    private PowerManager.WakeLock wakeLock;
    private boolean ttsReady,ttsInitialized,recognitionReady,running,ready,working,destroyed,mock,stopping,voiceEnabled,dialRequested;
    private int generation,remoteRetries;
    private String apiKey,workspaceId,accessId,accessSecret,utterance,outgoingText;
    private File wavFile,preRecordedFile;
    private Runnable sendTimeout,restartRemote,silentWatchdog;
    private int silentRetries;
    static Snapshot snapshot() { synchronized(LOCK) { return new Snapshot(status,localText,new ArrayList<>(HISTORY),active,busy,connected,voice); } }
    static final class Snapshot {
        final String status,localText; final List<String> history; final boolean active,busy,connected,voice;
        Snapshot(String s,String t,List<String> h,boolean a,boolean b,boolean c,boolean v) { status=s;localText=t;history=h;active=a;busy=b;connected=c;voice=v; }
    }
    private static final class Pending {
        final String text;
        final File file;
        final boolean translated;
        Pending(String text) { this(text, false); }
        Pending(String text, boolean translated) { this.text = text; this.file = null; this.translated = translated; }
        Pending(File file) { this.text = null; this.file = file; this.translated = false; }
    }
    @Override public void onCreate() {
        super.onCreate();
        File[] oldAudio=getCacheDir().listFiles((dir,name)->name.startsWith("translated-")&&name.endsWith(".wav"));
        if(oldAudio!=null)for(File file:oldAudio)file.delete();
        tts=new TextToSpeech(this,result->handler.post(()->{
            if(destroyed)return;
            ttsReady=result==TextToSpeech.SUCCESS && tts!=null && tts.setLanguage(Locale.US)>=TextToSpeech.LANG_AVAILABLE;
            ttsInitialized=true;
            if(!ttsReady)update("英语语音合成不可用，请安装系统英语语音包");
            if(running&&!stopping)maybeDial();
        }));
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            @Override public void onStart(String id){}
            @Override public void onDone(String id){handler.post(()->{if(current(id))uploadSynthesized();});}
            @Override public void onError(String id){handler.post(()->{if(current(id))finishSend("英语语音合成失败，请检查系统 TTS",false);});}
        });
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null)return START_NOT_STICKY;
        String action=intent.getAction();
        if(ACTION_STOP.equals(action))stopCall("正在挂断");
        else if(ACTION_START.equals(action)&&!running&&!stopping)startCall(intent.getStringExtra("number"));
        else if(ACTION_VOICE.equals(action)&&running&&ready&&!stopping)toggleVoice();
        else if(ACTION_SEND.equals(action)&&running&&ready&&!stopping)enqueue(intent.getStringExtra(EXTRA_TEXT));
        else if(ACTION_PLAY_PRERECORDED.equals(action)&&running&&ready&&!stopping)playPreRecorded(intent.getStringExtra(EXTRA_PRERECORDED_ID));
        if(!running&&!stopping)stopSelf();
        return START_NOT_STICKY;
    }
    private void startCall(String number) {
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("calls","电话翻译",NotificationManager.IMPORTANCE_LOW));
        try{startForeground(1,notification());}catch(RuntimeException error){update("无法启动通话服务，请检查麦克风权限");stopSelf();return;}
        try{
            apiKey=CredentialsStore.get(this,"bailianApiKey");workspaceId=CredentialsStore.get(this,"bailianWorkspaceId");
            accessId=CredentialsStore.get(this,"mtAccessKeyId");accessSecret=CredentialsStore.get(this,"mtAccessKeySecret");
            if(apiKey.isEmpty()||accessId.isEmpty()||accessSecret.isEmpty())throw new IllegalArgumentException();
            ParaformerProtocol.endpoint(workspaceId);GatewayProtocol.number(number);
            gateway=new GatewayClient(CredentialsStore.get(this,"gatewayUrl"),CredentialsStore.get(this,"gatewayToken"),new GatewayClient.Listener(){
                @Override public void onEvent(GatewayClient source,JSONObject e){handler.post(()->{if(valid(source))gatewayEvent(e);});}
                @Override public void onAudio(GatewayClient source,ByteString pcm){android.util.Log.d("PT","svc-onAudio size="+pcm.size());handler.post(()->{android.util.Log.d("PT","svc-onAudio-post remote="+(remote!=null));if(valid(source)&&remote!=null){armSilentWatchdog();remote.offerAudio(pcm);}});}
                @Override public void onFailure(GatewayClient source,String message){handler.post(()->{if(valid(source))stopCall(message);});}
            });
        }catch(Exception error){update("请检查号码及六项已保存的服务配置");stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return;}
        running=true;ready=false;mock=false;dialRequested=false;recognitionReady=false;generation++;remoteRetries=0;silentRetries=0;
        wakeLock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"telephone-translator:call");
        wakeLock.acquire(2*60*60*1000L+60000);
        synchronized(LOCK){localText="";HISTORY.clear();}
        update("正在连接网关");GatewayClient source=gateway;
        network.execute(()->{try{source.connect(number);}catch(Exception error){handler.post(()->{if(valid(source))stopCall(safe(error));});}});
    }
    private boolean valid(GatewayClient source){return running&&!destroyed&&!stopping&&gateway==source;}
    private void gatewayEvent(JSONObject e){
        String type=e.optString("event");
        if("attached".equals(type)){mock="mock".equals(e.optString("mode"));update(mock?"模拟模式：正在准备语音识别，不会拨打真实电话":"已连接网关，先准备对方语音识别");startRemote();}
        else if("status".equals(type)){
            String state=e.optString("state");
            if("connected".equals(state)){ready=true;startRemote();update(mock?"模拟通话已连接（不会呼叫对方）":"对方已接听，可以发送中文");}
            else if("ringing".equals(state))update("对方电话正在响铃");
            else if("answered".equals(state))update("对方已接听，正在建立音频线路");
            else if("ending".equals(state))stopCall("挂断尚未确认，请检查供应商控制台");
            else update("正在拨号，等待对方接听");
        }else if("played".equals(type)&&current(e.optString("utteranceId")))finishSend(e.optBoolean("simulated")?"模拟验证：译文音频已接收（未拨号、未远端播放）":"电话线路已完成英语译文播放",true);
        else if("ended".equals(type))endLocally("通话已结束："+endReason(e.optString("reason")));
        else if("error".equals(type))stopCall("电话线路异常；请检查网关和供应商控制台，不会自动重拨");
    }
    private String endReason(String r){
        if("busy".equals(r))return "对方忙线";if("no-answer".equals(r))return "无人接听";
        if("failed".equals(r))return "呼叫失败";if("timeout".equals(r))return "超时";return "线路已关闭";
    }
    private void startRemote(){
        if(!running||stopping||remote!=null)return;
        remote=new ParaformerRecognizer(new ParaformerRecognizer.Listener(){
            @Override public void onReady(ParaformerRecognizer s){handler.post(()->{
                if(remote!=s||!running||stopping)return;
                recognitionReady=true;
                if(!dialRequested)maybeDial();
                else update(mock?"模拟通话：等待测试音频":"正在接收对方语音并翻译为中文");
            });}
            @Override public void onSentence(ParaformerRecognizer s,String t){android.util.Log.d("PT","svc-sentence="+t);if(silentWatchdog!=null)handler.removeCallbacks(silentWatchdog);silentRetries=0;handler.post(()->{if(remote==s&&running&&!stopping){remoteRetries=0;translateIncoming(t);}});}
            @Override public void onError(ParaformerRecognizer s,String message,boolean retryable){android.util.Log.d("PT","svc-onError retry="+retryable+" msg="+message);handler.post(()->{
                if(remote!=s||!running||stopping)return;remote.close();remote=null;recognitionReady=false;
                if(!retryable||++remoteRetries>5){stopCall("对方语音识别失败："+message);return;}
                int token=generation;update(message+"；将重连识别（不会重新拨号）");
                restartRemote=()->{if(running&&!stopping&&token==generation)startRemote();};
                handler.postDelayed(restartRemote,Math.min(15000,1000L<<remoteRetries));
            });}
        },"zh",true);
        try{remote.start(apiKey,workspaceId);}catch(Exception error){stopCall("无法启动对方语音识别");}
    }
    private void maybeDial(){
        if(!running||stopping||dialRequested||!recognitionReady)return;
        if(!ttsInitialized){update("正在等待系统英语语音合成引擎就绪");return;}
        if(!ttsReady){stopCall("英语语音合成不可用；请安装英语语音包后重新拨号");return;}
        dialRequested=true;update(mock?"模拟模式：识别和英语语音引擎已就绪":"识别和英语语音引擎已就绪，正在拨号");gateway.dial();
    }
    private void toggleVoice(){
        if(voiceEnabled){stopVoice();update("中文语音输入已关闭；未完成的句子不会发送");return;}
        voiceEnabled=true;microphone=new ParaformerRecognizer(new ParaformerRecognizer.Listener(){
            @Override public void onReady(ParaformerRecognizer s){handler.post(()->{if(microphone==s&&running)update("中文语音输入已开启：说完一句后自动发送译文");});}
            @Override public void onSentence(ParaformerRecognizer s,String t){handler.post(()->{if(microphone==s&&running&&!stopping)enqueue(t,true);});}
            @Override public void onError(ParaformerRecognizer s,String m,boolean retryable){handler.post(()->{if(microphone==s&&running){stopVoice();update("中文语音输入已停止："+m);}});}
        },"en");update("正在开启中文语音输入");
        try{microphone.start(apiKey,workspaceId);}catch(Exception error){stopVoice();update("无法开启中文语音输入");}
    }
    private void stopVoice(){voiceEnabled=false;if(microphone!=null){microphone.close();microphone=null;}}
    private void translateIncoming(String translated){
        int token=generation;
        incoming.execute(()->{
            try{handler.post(()->{
                if(!running||stopping||destroyed||token!=generation)return;
                synchronized(LOCK){HISTORY.add(translated);if(HISTORY.size()>30)HISTORY.remove(0);}broadcast();
            });}catch(RejectedExecutionException error){update("对方语音翻译积压，已跳过一句，请检查网络");}
        });
    }
    private void enqueue(String text){ enqueue(text,false); }
    private void enqueue(String text,boolean translated){
        if(text==null||text.trim().isEmpty())return;synchronized(LOCK){localText=text;}
        if(text.length()>5000){update("单句超过 5000 字，请分段发送");return;}
        if(queued.size()>=10){stopVoice();update("待发送话语已达 10 条，语音输入已停止；请等待并重试未发送内容");return;}
        queued.add(new Pending(text.trim(),translated));processNext();broadcast();
    }
    private void processNext(){
        if(!running||stopping||!ready||working||queued.isEmpty())return;
        Pending pending=queued.peek();
        if(pending.text!=null&&!ttsReady){queued.clear();stopVoice();update("英语语音合成未就绪；本条未发送，请安装英语语音包后重试");return;}
        queued.remove();
        outgoingText=null;preRecordedFile=null;
        if(pending.text!=null)outgoingText=pending.text;else preRecordedFile=pending.file;
        working=true;utterance=UUID.randomUUID().toString();String currentId=utterance;int token=generation;
        update(pending.text!=null?"正在合成英语译文；排队 "+queued.size()+" 条":"正在播放预录音频到电话线路；排队 "+queued.size()+" 条");
        sendTimeout=()->{if(current(currentId))stopCall("译文发送或播放超时，已请求挂断；避免重复发送");};handler.postDelayed(sendTimeout,100000);
        GatewayClient source=gateway;
        if(pending.text!=null){
            final String text=pending.text;final boolean translated=pending.translated;
            outgoing.execute(()->{
                try{
                    if(text.length()>5000){handler.post(()->{if(current(currentId))finishSend("单句超过 5000 字，请分段发送",false);});return;}
                    if(translated){handler.post(()->{if(current(currentId))synthesizeEnglish(text,currentId);});return;}
                    String english=TranslatorClient.translate(text,"zh","en",accessId,accessSecret);
                    android.util.Log.d("PT","svc-translate="+text+" -> "+english);
                    handler.post(()->{if(current(currentId))synthesizeEnglish(english,currentId);});
                }catch(Exception error){handler.post(()->{if(current(currentId))finishSend("中文翻译失败："+safe(error),false);});}
            });
        }else{
            final File file=pending.file;
            outgoing.execute(()->{
                try{source.upload(file,currentId);}
                catch(Exception error){handler.post(()->{if(current(currentId))stopCall("预录音频发送未确认："+safe(error)+"；请检查线路后重新拨号");});}
            });
        }
    }
    private void synthesizeEnglish(String english,String currentId){
        if(!current(currentId))return;
        try{
            if(english.length()>TextToSpeech.getMaxSpeechInputLength()){finishSend("英语译文过长，请分段发送",false);return;}
            wavFile=new File(getCacheDir(),"translated-"+currentId+".wav");update("正在合成英语音频");
            if(tts.synthesizeToFile(english,new Bundle(),wavFile,currentId)==TextToSpeech.ERROR)
                finishSend("英语语音合成失败，请检查系统 TTS",false);
        }catch(Exception error){finishSend("英语语音合成失败："+safe(error),false);}
    }
    private void playPreRecorded(String id){
        if(id==null||id.trim().isEmpty())return;
        File file;
        try{file=PreRecordedAudio.file(this,id);}catch(Exception error){update("录音标识无效");return;}
        if(!file.isFile()||file.length()==0||file.length()>64L*1024*1024){update("预录音频不可用");return;}
        if(queued.size()>=10){update("待发送内容已满，请等待当前播放完成");return;}
        List<File> parts=splitForPlayback(file);
        for(File part:parts)queued.add(new Pending(part));
        processNext();broadcast();
    }
    /** 长预录音频按网关 60 秒上限切成多段；每段是独立 WAV，依次排队连续播放。 */
    private List<File> splitForPlayback(File source){
        List<File> single=new ArrayList<>();single.add(source);
        try(java.io.DataInputStream input=new java.io.DataInputStream(new java.io.FileInputStream(source))){
            byte[] tag=new byte[4];input.readFully(tag);input.skipBytes(4);input.readFully(tag);input.skipBytes(4);
            input.readFully(tag);input.skipBytes(4);int fmtSize=Integer.reverseBytes(input.readInt());
            short format=Short.reverseBytes(input.readShort());int channels=Short.reverseBytes(input.readShort());
            int sampleRate=Integer.reverseBytes(input.readInt());int byteRate=Integer.reverseBytes(input.readInt());
            input.skipBytes(2);short width=Short.reverseBytes(input.readShort());input.skipBytes(fmtSize-16);
            long dataOffset=44L+Math.max(0,fmtSize-16);long dataSize=0;
            while(true){input.readFully(tag);int size=Integer.reverseBytes(input.readInt());
                if(new String(tag,java.nio.charset.StandardCharsets.US_ASCII).equals("data")){dataSize=size&0xffffffffL;break;}
                input.skipBytes(size);dataOffset+=8+size;}
            if(format!=1||width!=2||channels<1||channels>2||byteRate<=0||dataSize<=0)return single;
            long chunk=Math.max(1024L,byteRate*60L);
            if(dataSize<=chunk)return single;
            File dir=new File(getCacheDir(),"prerecorded-split");dir.mkdirs();
            for(File old:dir.listFiles())old.delete();
            List<File> parts=new ArrayList<>();
            try(java.io.InputStream raw=new java.io.FileInputStream(source)){
                long skipped=dataOffset;while(skipped>0)skipped-=raw.skip(skipped);
                long remaining=dataSize;int index=0;
                while(remaining>0){
                    long take=Math.min(remaining,chunk);int data=(int)take;remaining-=take;
                    File part=new File(dir,"part-"+(index++)+".wav");
                    java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream(data+44);
                    java.nio.ByteBuffer header=java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));header.putInt(36+data);
                    header.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    header.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));header.putInt(16);
                    header.putShort((short)1);header.putShort((short)channels);header.putInt(sampleRate);
                    header.putInt(byteRate);header.putShort((short)(channels*width));header.putShort(width);
                    header.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));header.putInt(data);
                    out.write(header.array());
                    byte[] buffer=new byte[Math.min(65536,data)];int read;
                    int left=data;while(left>0&&(read=raw.read(buffer,0,Math.min(buffer.length,left)))!=-1){out.write(buffer,0,read);left-=read;}
                    try(java.io.FileOutputStream fos=new java.io.FileOutputStream(part)){fos.write(out.toByteArray());}
                    parts.add(part);
                }
            }
            return parts;
        }catch(Exception error){return single;}
    }
    private boolean current(String id){return running&&!stopping&&!destroyed&&working&&id!=null&&id.equals(utterance);}
    private void uploadSynthesized(){
        String id=utterance;File file=wavFile;GatewayClient source=gateway;update("正在发送英语译文到电话线路");
        outgoing.execute(()->{
            try{source.upload(file,id);}catch(Exception error){handler.post(()->{if(current(id))stopCall("英语音频发送未确认："+safe(error)+"；请检查线路后重新拨号");});}
            finally{file.delete();}
        });
    }
    private void finishSend(String message,boolean success){
        if(!working||!running||stopping)return;if(sendTimeout!=null)handler.removeCallbacks(sendTimeout);
        working=false;utterance=null;if(wavFile!=null){wavFile.delete();wavFile=null;}preRecordedFile=null;
        if(!success){if(outgoingText!=null)synchronized(LOCK){localText=outgoingText;}queued.clear();stopVoice();}update(message);if(success)processNext();
    }
    private String safe(Exception e){
        String m=e.getMessage();if(m!=null&&(m.startsWith("网关")||m.startsWith("英语")||m.startsWith("翻译")||m.startsWith("挂断")))return m;
        return "请求失败，请检查网络和服务配置";
    }
    private void stopCall(String message){
        if(!running||stopping)return;stopping=true;ready=false;stopVoice();closeRemote();queued.clear();if(tts!=null)tts.stop();update(message+"；正在确认挂断");
        GatewayClient source=gateway;network.execute(()->{
            String result="通话已挂断";try{source.hangup();}catch(Exception error){result="挂断未确认；服务器会继续清理，请立即核查供应商控制台";}
            final String finalResult=result;handler.post(()->{if(!destroyed&&gateway==source)endLocally(message+"；"+finalResult);});
        });
    }
    private void closeRemote(){if(restartRemote!=null){handler.removeCallbacks(restartRemote);restartRemote=null;}if(silentWatchdog!=null){handler.removeCallbacks(silentWatchdog);silentWatchdog=null;}if(remote!=null){remote.close();remote=null;}}
    private void armSilentWatchdog(){
        if(silentWatchdog!=null)handler.removeCallbacks(silentWatchdog);
        silentWatchdog=()->{
            if(!running||stopping||remote==null)return;
            if(++silentRetries>3){silentWatchdog=null;update("识别多次无响应，请重新拨号后重试");return;}
            android.util.Log.d("PT","svc-silent-selfheal retry="+silentRetries);
            closeRemote();startRemote();
        };
        handler.postDelayed(silentWatchdog,12000);
    }
    private void endLocally(String message){
        running=false;ready=false;working=false;stopping=false;generation++;utterance=null;outgoingText=null;preRecordedFile=null;stopVoice();closeRemote();queued.clear();incoming.getQueue().clear();
        if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();
        if(sendTimeout!=null)handler.removeCallbacks(sendTimeout);if(tts!=null)tts.stop();if(gateway!=null)gateway.close();if(wavFile!=null){wavFile.delete();wavFile=null;}
        update(message);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    private void update(String message){synchronized(LOCK){status=message;active=running;connected=ready&&!stopping;busy=working||stopping;voice=voiceEnabled;}broadcast();}
    private void broadcast(){sendBroadcast(new Intent(ACTION_UPDATE).setPackage(getPackageName()));}
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ListeningService.class).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"calls").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("电话翻译")
            .setContentText("接收对方语音；中文原声仅用于识别").setContentIntent(open).addAction(android.R.drawable.ic_media_pause,"挂断",stop).setOngoing(true).build();
    }
    @Override public void onDestroy(){
        destroyed=true;running=false;ready=false;working=false;generation++;utterance=null;outgoingText=null;preRecordedFile=null;stopVoice();closeRemote();handler.removeCallbacksAndMessages(null);
        if(gateway!=null)gateway.close();incoming.shutdownNow();outgoing.shutdownNow();network.shutdown();if(tts!=null){tts.stop();tts.shutdown();}if(wavFile!=null)wavFile.delete();
        if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();
        synchronized(LOCK){active=false;connected=false;busy=false;voice=false;}broadcast();super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
