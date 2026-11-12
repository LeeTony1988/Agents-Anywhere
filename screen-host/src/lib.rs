//! aa-screen-host library crate. The binary (`main.rs`) is a thin stdio loop over
//! [`Host`].

pub mod capture;
pub mod config;
pub mod host;
pub mod input;
#[cfg(windows)]
pub mod overlay;
pub mod pointer;
pub mod protocol;
pub mod publish;
