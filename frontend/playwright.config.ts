import { defineConfig } from '@playwright/test';
export default defineConfig({testDir:'./e2e',fullyParallel:false,workers:1,use:{baseURL:process.env.E2E_URL||'http://localhost:5173',trace:'retain-on-failure'},reporter:[['list'],['html',{open:'never'}]],webServer:process.env.E2E_EXTERNAL?undefined:{command:'npm run dev',url:'http://localhost:5173',reuseExistingServer:true}});
