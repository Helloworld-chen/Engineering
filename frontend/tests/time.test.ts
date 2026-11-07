import { describe,it,expect } from 'vitest';
import { remainingMillis, formatDuration } from '../src/lib/time';
describe('后端期限展示',()=>{
 it('截止时间按服务端时差计算且不小于零',()=>{expect(remainingMillis('2026-11-07T10:02:00.000Z','2026-11-07T10:01:00.000Z',5000)).toBe(55000);expect(remainingMillis('2026-11-07T10:02:00.000Z','2026-11-07T10:01:00.000Z',61000)).toBe(0)});
 it('暂停期间保持原剩余时间',()=>expect(remainingMillis(null,'2026-11-07T10:01:00.000Z',999999,28000)).toBe(28000));
 it('长时间显示天小时分钟秒',()=>expect(formatDuration(172801000)).toBe('2天 00:00:01'));
});
