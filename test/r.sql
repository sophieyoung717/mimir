--SELECT A, B FROM R;
--SELECT A, SUM(B) FROM R GROUP BY A;
SELECT R.A, S.B, S.C FROM R, S WHERE R.B = S.B