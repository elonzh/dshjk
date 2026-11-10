import readline from 'node:readline';
import {readFileSync} from 'node:fs';
const send = x => process.stdout.write(JSON.stringify({jsonrpc:'2.0',...x})+'\n');
const note = (method,params) => send({method,params});
const event = (sessionId,type,data) => note('session.event',{sessionId,event:{type,seq:1,time:1,data}});
let next=0;
const waiting = new Map();
const request = (id,method,params) => new Promise(resolve => { waiting.set(id,resolve); send({id,method,params}); });
function finish(id,sessionId,text) {
 event(sessionId,'assistant/message',{turn:1,step:1,stream:[],message:{id:'a',role:'assistant',source:{kind:'model'},content:[{type:'text',text}]}});
 event(sessionId,'turn/end',{turn:1,reason:{kind:'completed'}});
 note('session.status',{sessionId,status:'idle'});
}
readline.createInterface({input:process.stdin}).on('line',async line => {
 const m=JSON.parse(line), p=m.params??{}, sessionId=p.sessionId;
 if(!m.method) {waiting.get(m.id)?.(m.result??m.error);return;}
 const result = x => send({id:m.id,result:x});
 switch(m.method) {
 case 'initialize':
  if(process.env.FIXTURE_MODE==='hang') return;
  console.error('fixture diagnostic');
  process.stdout.write('non-json diagnostic\n');
  return result({serverInfo:{name:'deepseek-harness-sdk-runtime',version:'fixture'},initialize:p});
 case 'session/working-directory/get': return result({cwd:p.sessionId==='nested'?'/nested':process.cwd()});
 case 'session/working-directory/set': return result({cwd:p.path});
 case 'session/resume': case 'session/wait': return result({});
 case 'launch-config': {
  const paths = process.argv.flatMap((arg, i) => arg === '--patch' ? [process.argv[i+1]] : []);
  return result({paths, patches: paths.map(path => JSON.parse(readFileSync(path, 'utf8')))});
 }
 case 'error': return send({id:m.id,error:{code:42,message:'expected error',data:{detail:'retained'}}});
 case 'hang': return;
 case 'crash': process.exit(7); break;
 case 'flood': for(let i=0;i<20;i++)note('session.status',{sessionId:'flood',status:'running'}); return result({});
 case 'session/prompt': return result({messageId:'low-level'});
 case 'session/query': {
  const text = p.contentBlocks[0]?.text;
  if(text==='malformed')return result({kind:'command',execution:{commandId:'c',result:{kind:'success',sourceEventSeq:-1}}});
  if(text?.startsWith('/')) {
   const commandId='cmd'+(++next);
   event(sessionId,'command/run',{commandId,name:'compact',source:{kind:'user'}});
   if(text==='/early')note('session.query.completed',{sessionId,commandId,finalResponse:'command complete',finishReason:'completed'});
   if(text!=='/missing')event(sessionId,'command/done',{commandId,kind:'success'});
   note('session.query.completed',{sessionId,commandId,finalResponse:'command complete',finishReason:'completed'});
   return result({kind:'command',execution:{commandId,result:{kind:'success'}}});
  }
  const messageId='msg'+(++next);
  note('session.status',{sessionId,status:'idle'}); // pre-receipt must not end run
  event(sessionId,'agent/inbox/spliced',{target:'next-turn',start:0,inserted:[{id:messageId,role:'user',source:{kind:'user'},content:[{type:'text',text}]}]});
  result({kind:'prompt',messageId});
  note('subagent.started',{parentSessionId:sessionId,childSessionId:sessionId+'-child'});
  event(sessionId+'-child','assistant/message',{turn:1,step:1,stream:[],message:{id:'child',role:'assistant',source:{kind:'model'},content:[{type:'text',text:'child output'}]}});
  if(text==='approval') {
   const answer=await request(701,'approval/request',{sessionId,toolName:'bash',reason:'test'});
   return finish(m.id,sessionId,answer.outcome);
  }
  if(text==='question'||text==='cancel-question') {
   const promise=request('ask_'+sessionId,'user-question/ask',{sessionId,requestId:sessionId,questions:[{id:'q',question:'Choose',options:[{label:'yes'}]}]});
   if(text==='cancel-question') {
    setTimeout(()=>{note('user-question.cancelled',{requestId:sessionId,sessionId});finish(m.id,sessionId,'cancelled');},50);
    return;
   }
   const answer=await promise;
   return finish(m.id,sessionId,answer.answers[0].selected[0]);
  }
  if(text==='bad-event') {event(sessionId,'turn/end',{reason:{}});return;}
  setTimeout(()=>finish(m.id,sessionId,text??'image'),20);
  break;
 }
 case 'shutdown': if(process.env.FIXTURE_MODE==='stubborn')return; result({}); process.exit(0); break;
 default: return result({});
 }
});
if(process.env.FIXTURE_MODE==='stubborn') {process.on('SIGTERM',()=>{});setInterval(()=>{},1000);}
