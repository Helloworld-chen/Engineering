import {ref} from 'vue';

export function useAction() {
 const busy = ref(false);
 const error = ref('');
 const success = ref('');
 async function run(operation: () => Promise<void>, message = '') {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  success.value = '';
  try {
   await operation();
   success.value = message;
  } catch (failure) {
   error.value = failure instanceof Error ? failure.message : '操作失败，请重试';
  } finally {
   busy.value = false;
  }
 }
 return {busy, error, success, run};
}
