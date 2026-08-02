package id.co.nativeapp.employee.payroll.service;

/**
 * The generated net-pay bank file (Track P phase P5): the CSV body plus the facts the controller
 * needs to name the download ({@code payroll-{period}-seq{n}.csv}).
 */
public record BankFileResult(String csv, String period, int runSeq) {}
