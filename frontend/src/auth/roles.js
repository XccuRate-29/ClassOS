export const ROLE_LABELS = {
  ADMIN: 'Admin',
  TEACHER: 'Teacher',
  STUDENT: 'Student',
  LAB_ASSISTANT: 'Lab assistant',
};

export const ROLE_HOME = {
  ADMIN: '/admin',
  TEACHER: '/teacher',
  STUDENT: '/student',
  LAB_ASSISTANT: '/lab',
};

export function homeFor(role) {
  return ROLE_HOME[role] || '/login';
}
