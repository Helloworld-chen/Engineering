/** elapsed is measured with performance.now(), independent of the browser wall clock. */
export function remainingMillis(deadline:string|null,serverNow:string,elapsed:number,paused?:number|null):number {
 if(paused!=null) return Math.max(0,paused);
 if(!deadline) return 0;
 return Math.max(0,Date.parse(deadline)-Date.parse(serverNow)-elapsed);
}
export function formatDuration(ms:number):string {
 const seconds=Math.max(0,Math.ceil(ms/1000));
 const days=Math.floor(seconds/86400);
 const time=[Math.floor(seconds%86400/3600),Math.floor(seconds%3600/60),seconds%60].map(v=>String(v).padStart(2,'0')).join(':');
 return (days?`${days}天 `:'')+time;
}
