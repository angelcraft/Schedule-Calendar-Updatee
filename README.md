# ShiftScheduleSync OpenCV + OCR MVP

This version adds:
- OpenCV preprocessing for perspective correction and table enhancement
- OCR with ML Kit
- Cell-by-cell extraction when grid lines are detected
- CSV export to Downloads/Schedules/Horario-Semana-Que-viene.csv
- Moves processed images from Downloads/Schedules/toSync to Downloads/Schedules/Synced

Notes:
- Best with one weekly table per image, decent lighting and visible borders.
- Handwritten annotations are de-prioritized, not fully solved.
